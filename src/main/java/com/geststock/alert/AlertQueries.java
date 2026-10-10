package com.geststock.alert;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.geststock.alert.AlertRules.CashSessionRow;
import com.geststock.alert.AlertRules.CustomerDebt;
import com.geststock.alert.AlertRules.OpenPurchaseOrder;
import com.geststock.alert.AlertRules.PendingInventory;
import com.geststock.alert.AlertRules.StockPosition;

/**
 * Lectures SQL des candidats aux alertes (lecture seule, aucune écriture). Chaque requête est
 * restreinte au dépôt demandé, ou à tous les dépôts actifs si {@code warehouseId} est nul.
 */
@Component
class AlertQueries {

	private final NamedParameterJdbcTemplate jdbc;

	AlertQueries(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Couples produit × dépôt suivis : ceux qui ont déjà un niveau de stock, plus les produits
	 * actifs jamais approvisionnés dans le magasin principal (ils y sont en rupture).
	 */
	List<StockPosition> stockPositions(UUID warehouseId, boolean withDormancy) {
		String dormancy = withDormancy ? """
				left join lateral (
				    select coalesce(max(m.created_at) filter (where m.type in ('SALE', 'TRANSFER_OUT')),
				                    min(m.created_at)) as dormant_since
				    from stock_movement m
				    where m.warehouse_id = w.id and m.product_id = p.id and l.quantity > 0) mv on true
				""" : "left join lateral (select null::timestamptz as dormant_since) mv on true\n";
		String sql = """
				select w.id as warehouse_id, w.name as warehouse_name, p.id as product_id, p.sku, p.name, p.active,
				       p.alert_threshold, p.max_stock_level, coalesce(l.quantity, 0) as quantity, mv.dormant_since
				from warehouse w
				cross join product p
				left join stock_level l on l.warehouse_id = w.id and l.product_id = p.id
				""" + dormancy + """
				where w.active
				  and (l.product_id is not null or (w.main and p.active))
				""" + warehouseClause("w.id", warehouseId);
		return this.jdbc.query(sql, params(warehouseId),
				(rs, i) -> new StockPosition(uuid(rs, "warehouse_id"), rs.getString("warehouse_name"),
						uuid(rs, "product_id"), rs.getString("sku"), rs.getString("name"), rs.getBoolean("active"),
						rs.getInt("alert_threshold"), (Integer) rs.getObject("max_stock_level"), rs.getInt("quantity"),
						instant(rs, "dormant_since")));
	}

	/** Commandes ouvertes dont la date prévue est antérieure à {@code expectedBefore}. */
	List<OpenPurchaseOrder> overduePurchaseOrders(UUID warehouseId, LocalDate expectedBefore) {
		String sql = """
				select po.id, po.number, po.supplier_name, po.warehouse_id, w.name as warehouse_name,
				       po.expected_delivery_date, po.total_amount, po.received_amount
				from purchase_order po
				join warehouse w on w.id = po.warehouse_id
				where po.status in ('ORDERED', 'PARTIALLY_RECEIVED')
				  and po.expected_delivery_date < :expectedBefore
				""" + warehouseClause("po.warehouse_id", warehouseId);
		return this.jdbc.query(sql, params(warehouseId).addValue("expectedBefore", expectedBefore),
				(rs, i) -> new OpenPurchaseOrder(uuid(rs, "id"), rs.getString("number"), rs.getString("supplier_name"),
						uuid(rs, "warehouse_id"), rs.getString("warehouse_name"),
						rs.getObject("expected_delivery_date", LocalDate.class), rs.getBigDecimal("total_amount"),
						rs.getBigDecimal("received_amount")));
	}

	/**
	 * Clients débiteurs. Les règlements, avoirs et annulations sont imputés aux plus anciennes
	 * créances (premier entré, premier soldé) ; une créance est échue quand sa date + le délai
	 * de paiement du client (ou le délai par défaut) est dépassée.
	 */
	List<CustomerDebt> customerDebts(int defaultTermsDays, Instant now) {
		String sql = """
				with debits as (
				    select e.customer_id, e.amount, e.created_at,
				           sum(e.amount) over (partition by e.customer_id order by e.created_at, e.id) as cumulated
				    from customer_account_entry e
				    where e.direction = 'DEBIT'),
				credits as (
				    select customer_id, sum(amount) as total
				    from customer_account_entry
				    where direction = 'CREDIT'
				    group by customer_id)
				select c.id, c.code, c.name, c.credit_limit, t.terms,
				       sum(d.amount) - coalesce(cr.total, 0) as balance,
				       coalesce(sum(greatest(0, least(d.amount, d.cumulated - coalesce(cr.total, 0))))
				                filter (where d.created_at + make_interval(days => t.terms) < :now), 0) as overdue,
				       min(d.created_at + make_interval(days => t.terms))
				           filter (where d.cumulated > coalesce(cr.total, 0)
				                     and d.created_at + make_interval(days => t.terms) < :now) as oldest_due
				from customer c
				cross join lateral (
				    select case when c.payment_terms_days > 0 then c.payment_terms_days else :defaultTerms end as terms) t
				join debits d on d.customer_id = c.id
				left join credits cr on cr.customer_id = c.id
				group by c.id, c.code, c.name, c.credit_limit, t.terms, cr.total
				having sum(d.amount) - coalesce(cr.total, 0) > 0
				""";
		return this.jdbc.query(sql,
				new MapSqlParameterSource("defaultTerms", defaultTermsDays).addValue("now", timestamp(now)),
				(rs, i) -> new CustomerDebt(uuid(rs, "id"), rs.getString("code"), rs.getString("name"),
						rs.getBigDecimal("credit_limit"), rs.getInt("terms"), rs.getBigDecimal("balance"),
						rs.getBigDecimal("overdue"), instant(rs, "oldest_due")));
	}

	/** Caisses ouvertes avant {@code openedBefore}. */
	List<CashSessionRow> cashSessionsOpenSince(UUID warehouseId, Instant openedBefore) {
		String sql = cashSelect() + """
				where s.status = 'OPEN' and s.opened_at < :openedBefore
				""" + warehouseClause("s.warehouse_id", warehouseId);
		return this.jdbc.query(sql, params(warehouseId).addValue("openedBefore", timestamp(openedBefore)),
				AlertQueries::cashSession);
	}

	/** Caisses clôturées depuis {@code closedAfter} avec un écart supérieur à la tolérance. */
	List<CashSessionRow> cashDifferences(UUID warehouseId, Instant closedAfter, BigDecimal tolerance) {
		String sql = cashSelect() + """
				where s.status = 'CLOSED' and s.closed_at >= :closedAfter and abs(s.difference) > :tolerance
				""" + warehouseClause("s.warehouse_id", warehouseId);
		return this.jdbc.query(sql,
				params(warehouseId).addValue("closedAfter", timestamp(closedAfter)).addValue("tolerance", tolerance),
				AlertQueries::cashSession);
	}

	List<PendingInventory> inventoriesAwaitingValidation(UUID warehouseId) {
		String sql = """
				select i.id, i.number, i.warehouse_id, w.name as warehouse_name, i.submitted_at,
				       (select count(*) from inventory_line l where l.inventory_id = i.id) as line_count
				from inventory_session i
				join warehouse w on w.id = i.warehouse_id
				where i.status = 'VALIDATION'
				""" + warehouseClause("i.warehouse_id", warehouseId);
		return this.jdbc.query(sql, params(warehouseId),
				(rs, i) -> new PendingInventory(uuid(rs, "id"), rs.getString("number"), uuid(rs, "warehouse_id"),
						rs.getString("warehouse_name"), instant(rs, "submitted_at"), rs.getInt("line_count")));
	}

	// --- Outils -------------------------------------------------------------------------------------

	private static String cashSelect() {
		return """
				select s.id, s.number, s.warehouse_id, w.name as warehouse_name, s.opened_at, s.closed_at, s.difference,
				       btrim(coalesce(u.first_name || ' ', '') || coalesce(u.last_name, u.username)) as cashier_name
				from cash_session s
				join warehouse w on w.id = s.warehouse_id
				join app_user u on u.id = s.cashier_id
				""";
	}

	private static CashSessionRow cashSession(ResultSet rs, int row) throws SQLException {
		return new CashSessionRow(uuid(rs, "id"), rs.getString("number"), uuid(rs, "warehouse_id"),
				rs.getString("warehouse_name"), rs.getString("cashier_name"), instant(rs, "opened_at"),
				instant(rs, "closed_at"), rs.getBigDecimal("difference"));
	}

	private static String warehouseClause(String column, UUID warehouseId) {
		return warehouseId != null ? "  and " + column + " = :warehouseId\n" : "";
	}

	private static MapSqlParameterSource params(UUID warehouseId) {
		MapSqlParameterSource params = new MapSqlParameterSource();
		if (warehouseId != null) {
			params.addValue("warehouseId", warehouseId);
		}
		return params;
	}

	private static OffsetDateTime timestamp(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	private static UUID uuid(ResultSet rs, String column) throws SQLException {
		return rs.getObject(column, UUID.class);
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value != null ? value.toInstant() : null;
	}

}
