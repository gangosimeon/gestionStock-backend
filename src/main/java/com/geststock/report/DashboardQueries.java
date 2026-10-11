package com.geststock.report;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Agrégats SQL du tableau de bord, en lecture seule, directement sur PostgreSQL. Chaque requête
 * borne les dates par des instants (index utilisables) et regroupe par jour dans le fuseau de
 * l'entreprise ; le dépôt est optionnel (tous les dépôts s'il est nul).
 */
@Component
class DashboardQueries {

	private final NamedParameterJdbcTemplate jdbc;

	DashboardQueries(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Agrégats bruts d'un jour : ventes validées, retours, coût des articles revendables retournés. */
	record DayRow(LocalDate day, long count, BigDecimal grossSales, BigDecimal cost, BigDecimal returns,
			BigDecimal restockedCost) {
	}

	/** Une ligne par jour de la période, jours sans activité compris. */
	List<DayRow> salesByDay(LocalDate from, LocalDate to, ZoneId zone, UUID warehouseId) {
		String sql = """
				with days as (
				    select cast(generate_series(cast(:fromDate as date), cast(:toDate as date), interval '1 day') as date) as day),
				sales as (
				    select cast(s.created_at at time zone :zone as date) as day, count(*) as cnt,
				           sum(s.total) as gross, sum(s.cost_total) as cost
				    from sale s
				    where s.status = 'COMPLETED' and s.created_at >= :from and s.created_at < :to %1$s
				    group by 1),
				returns as (
				    select cast(r.created_at at time zone :zone as date) as day, sum(r.total_amount) as amount
				    from customer_return r
				    where r.created_at >= :from and r.created_at < :to %2$s
				    group by 1),
				restocked as (
				    select cast(r.created_at at time zone :zone as date) as day, sum(l.quantity * l.unit_cost) as cost
				    from customer_return_line l
				    join customer_return r on r.id = l.return_id
				    where l.item_condition = 'RESELLABLE' and r.created_at >= :from and r.created_at < :to %2$s
				    group by 1)
				select d.day, coalesce(s.cnt, 0) as cnt, coalesce(s.gross, 0) as gross, coalesce(s.cost, 0) as cost,
				       coalesce(r.amount, 0) as returns, coalesce(k.cost, 0) as restocked
				from days d
				left join sales s on s.day = d.day
				left join returns r on r.day = d.day
				left join restocked k on k.day = d.day
				order by d.day""".formatted(scope("s.warehouse_id", warehouseId), scope("r.warehouse_id", warehouseId));
		return this.jdbc.query(sql, period(from, to, zone, warehouseId),
				(rs, i) -> new DayRow(rs.getObject("day", LocalDate.class), rs.getLong("cnt"), rs.getBigDecimal("gross"),
						rs.getBigDecimal("cost"), rs.getBigDecimal("returns"), rs.getBigDecimal("restocked")));
	}

	/** Ventes d'un vendeur sur une période : {@code [validées, total, payé, crédit, annulées, dernière vente]}. */
	Object[] sellerDay(UUID sellerId, LocalDate day, ZoneId zone) {
		String sql = """
				select count(*) filter (where s.status = 'COMPLETED' and s.created_at >= :from and s.created_at < :to) as cnt,
				       coalesce(sum(s.total) filter (where s.status = 'COMPLETED' and s.created_at >= :from and s.created_at < :to), 0) as total,
				       coalesce(sum(s.paid_amount) filter (where s.status = 'COMPLETED' and s.created_at >= :from and s.created_at < :to), 0) as paid,
				       coalesce(sum(s.credit_amount) filter (where s.status = 'COMPLETED' and s.created_at >= :from and s.created_at < :to), 0) as credit,
				       count(*) filter (where s.status = 'CANCELLED' and s.cancelled_at >= :from and s.cancelled_at < :to) as cancelled,
				       max(s.created_at) filter (where s.status = 'COMPLETED' and s.created_at >= :from and s.created_at < :to) as last_sale
				from sale s
				where s.created_by = :sellerId
				  and ((s.created_at >= :from and s.created_at < :to) or (s.cancelled_at >= :from and s.cancelled_at < :to))""";
		MapSqlParameterSource params = period(day, day, zone, null).addValue("sellerId", sellerId);
		return this.jdbc.queryForObject(sql, params, (rs, i) -> new Object[] { rs.getLong("cnt"), rs.getBigDecimal("total"),
				rs.getBigDecimal("paid"), rs.getBigDecimal("credit"), rs.getLong("cancelled"),
				rs.getObject("last_sale", OffsetDateTime.class) });
	}

	/** Règlements de dettes clients encaissés par l'utilisateur sur la journée : {montant, nombre}. */
	Object[] sellerCustomerPayments(UUID sellerId, LocalDate day, ZoneId zone) {
		String sql = """
				select coalesce(sum(e.amount), 0) as amount, count(*) as cnt
				from customer_account_entry e
				where e.entry_type = 'PAYMENT' and e.created_by = :sellerId
				  and e.created_at >= :from and e.created_at < :to""";
		MapSqlParameterSource params = period(day, day, zone, null).addValue("sellerId", sellerId);
		return this.jdbc.queryForObject(sql, params,
				(rs, i) -> new Object[] { rs.getBigDecimal("amount"), rs.getLong("cnt") });
	}

	/** Ventes validées d'un vendeur, jour par jour sur la période (jours sans vente compris). */
	List<Object[]> sellerSalesByDay(UUID sellerId, LocalDate from, LocalDate to, ZoneId zone) {
		String sql = """
				with days as (
				    select cast(generate_series(cast(:fromDate as date), cast(:toDate as date), interval '1 day') as date) as day),
				sales as (
				    select cast(s.created_at at time zone :zone as date) as day, count(*) as cnt, sum(s.total) as total
				    from sale s
				    where s.created_by = :sellerId and s.status = 'COMPLETED' and s.created_at >= :from and s.created_at < :to
				    group by 1)
				select d.day, coalesce(s.cnt, 0) as cnt, coalesce(s.total, 0) as total
				from days d left join sales s on s.day = d.day
				order by d.day""";
		MapSqlParameterSource params = period(from, to, zone, null).addValue("sellerId", sellerId);
		return this.jdbc.query(sql, params, (rs, i) -> new Object[] { rs.getObject("day", LocalDate.class),
				rs.getLong("cnt"), rs.getBigDecimal("total") });
	}

	long cancelledSales(LocalDate from, LocalDate to, ZoneId zone, UUID warehouseId) {
		String sql = """
				select count(*) from sale s
				where s.status = 'CANCELLED' and s.cancelled_at >= :from and s.cancelled_at < :to %s"""
			.formatted(scope("s.warehouse_id", warehouseId));
		Long count = this.jdbc.queryForObject(sql, period(from, to, zone, warehouseId), Long.class);
		return count != null ? count : 0;
	}

	/** {@code [nombre de réceptions, montant reçu, nombre de commandes, montant commandé]}. */
	Object[] purchases(LocalDate from, LocalDate to, ZoneId zone, UUID warehouseId) {
		String sql = """
				select (select count(*) from goods_receipt g
				        where g.received_at >= :from and g.received_at < :to %1$s) as receipts,
				       (select coalesce(sum(g.total_amount), 0) from goods_receipt g
				        where g.received_at >= :from and g.received_at < :to %1$s) as received,
				       (select count(*) from purchase_order o
				        where o.status <> 'CANCELLED' and o.created_at >= :from and o.created_at < :to %2$s) as orders,
				       (select coalesce(sum(o.total_amount), 0) from purchase_order o
				        where o.status <> 'CANCELLED' and o.created_at >= :from and o.created_at < :to %2$s) as ordered"""
			.formatted(scope("g.warehouse_id", warehouseId), scope("o.warehouse_id", warehouseId));
		return this.jdbc.queryForObject(sql, period(from, to, zone, warehouseId),
				(rs, i) -> new Object[] { rs.getLong("receipts"), rs.getBigDecimal("received"), rs.getLong("orders"),
						rs.getBigDecimal("ordered") });
	}

	/** {@code [unités, valeur au dernier prix d'achat, produits en stock]} à l'instant. */
	Object[] stockNow(UUID warehouseId) {
		String sql = """
				select coalesce(sum(l.quantity), 0) as units,
				       coalesce(sum(l.quantity * p.purchase_price) filter (where l.quantity > 0), 0) as value,
				       count(distinct l.product_id) filter (where l.quantity > 0) as products
				from stock_level l
				join product p on p.id = l.product_id
				where true %s""".formatted(scope("l.warehouse_id", warehouseId));
		return this.jdbc.queryForObject(sql, params(warehouseId),
				(rs, i) -> new Object[] { rs.getLong("units"), rs.getBigDecimal("value"), rs.getLong("products") });
	}

	/** Produit vendu sur la période, ventes et retours de la période. */
	record ProductRow(UUID productId, String sku, String name, long quantity, BigDecimal revenue, BigDecimal profit) {
	}

	List<ProductRow> topProducts(LocalDate from, LocalDate to, ZoneId zone, UUID warehouseId, int limit) {
		String sql = """
				with sold as (
				    select l.product_id, sum(l.quantity) as qty, sum(l.line_total) as revenue, sum(l.cost_total) as cost,
				           max(l.product_sku) as sku, max(l.product_name) as name
				    from sale_line l
				    join sale s on s.id = l.sale_id
				    where s.status = 'COMPLETED' and s.created_at >= :from and s.created_at < :to %1$s
				    group by l.product_id),
				returned as (
				    select l.product_id, sum(l.quantity) as qty, sum(l.amount) as amount,
				           coalesce(sum(l.quantity * l.unit_cost) filter (where l.item_condition = 'RESELLABLE'), 0) as cost
				    from customer_return_line l
				    join customer_return r on r.id = l.return_id
				    where r.created_at >= :from and r.created_at < :to %2$s
				    group by l.product_id)
				select s.product_id, coalesce(p.sku, s.sku) as sku, coalesce(p.name, s.name) as name,
				       s.qty - coalesce(r.qty, 0) as quantity,
				       s.revenue - coalesce(r.amount, 0) as revenue,
				       (s.revenue - coalesce(r.amount, 0)) - (s.cost - coalesce(r.cost, 0)) as profit
				from sold s
				left join returned r on r.product_id = s.product_id
				left join product p on p.id = s.product_id
				order by revenue desc, quantity desc, sku
				limit :limit""".formatted(scope("s.warehouse_id", warehouseId), scope("r.warehouse_id", warehouseId));
		return this.jdbc.query(sql, period(from, to, zone, warehouseId).addValue("limit", limit),
				(rs, i) -> new ProductRow(rs.getObject("product_id", UUID.class), rs.getString("sku"),
						rs.getString("name"), rs.getLong("quantity"), rs.getBigDecimal("revenue"),
						rs.getBigDecimal("profit")));
	}

	/** Variation nette du stock par jour, depuis {@code from} (inclus). */
	Map<LocalDate, Long> netMovementsByDay(Instant from, ZoneId zone, UUID warehouseId) {
		String sql = """
				select cast(m.created_at at time zone :zone as date) as day, sum(m.quantity) as net
				from stock_movement m
				where m.created_at >= :from %s
				group by 1""".formatted(scope("m.warehouse_id", warehouseId));
		Map<LocalDate, Long> result = new HashMap<>();
		this.jdbc.query(sql, params(warehouseId).addValue("from", timestamp(from)).addValue("zone", zone.getId()),
				(ResultSet rs) -> {
					result.put(rs.getObject("day", LocalDate.class), rs.getLong("net"));
				});
		return result;
	}

	// --- Outils -------------------------------------------------------------------------------------

	private static String scope(String column, UUID warehouseId) {
		return warehouseId != null ? "and " + column + " = :warehouseId" : "";
	}

	private static MapSqlParameterSource params(UUID warehouseId) {
		MapSqlParameterSource params = new MapSqlParameterSource();
		if (warehouseId != null) {
			params.addValue("warehouseId", warehouseId);
		}
		return params;
	}

	/** Bornes de la période en instants : début du premier jour, début du lendemain du dernier. */
	private static MapSqlParameterSource period(LocalDate from, LocalDate to, ZoneId zone, UUID warehouseId) {
		return params(warehouseId).addValue("fromDate", from)
			.addValue("toDate", to)
			.addValue("from", timestamp(from.atStartOfDay(zone).toInstant()))
			.addValue("to", timestamp(to.plusDays(1).atStartOfDay(zone).toInstant()))
			.addValue("zone", zone.getId());
	}

	private static OffsetDateTime timestamp(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

}
