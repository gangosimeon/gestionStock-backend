package com.geststock.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.geststock.common.api.PageQuery;
import com.geststock.common.api.SortWhitelist;
import com.geststock.exception.InvalidRequestException;
import com.geststock.report.ReportSql.Criteria;
import com.geststock.report.dto.ReportFilter;
import com.geststock.report.dto.ReportPage;
import com.geststock.report.dto.ReportRows.ProductSalesRow;
import com.geststock.report.dto.ReportRows.ProductSalesSummary;
import com.geststock.report.dto.ReportRows.SaleRow;
import com.geststock.report.dto.ReportRows.SaleSummary;
import com.geststock.report.dto.ReportRows.SalesGroupRow;
import com.geststock.report.dto.ReportRows.SalesPeriodRow;

import static com.geststock.report.ReportScope.instant;
import static com.geststock.report.ReportScope.money;
import static com.geststock.report.ReportScope.rate;
import static com.geststock.report.ReportScope.uuid;

/**
 * Rapports de ventes : liste des ventes, ventes par produit, séries (jour / mois / année),
 * performance par vendeur et par dépôt. Ventes validées ({@code COMPLETED}) sauf demande
 * explicite de statut ; retours comptés à leur date (vue des flux), sauf dans la liste des
 * ventes où ils sont rattachés à leur vente.
 */
@Service
@Transactional(readOnly = true)
class SalesReportService {

	static final SortWhitelist SALE_SORTS = new SortWhitelist(Map.of("createdAt", "created_at", "number", "number",
			"total", "total", "profit", "profit", "customerName", "customer_name", "sellerName", "seller_name"),
			Sort.by(Sort.Direction.DESC, "created_at"));

	static final SortWhitelist PRODUCT_SORTS = new SortWhitelist(Map.of("netRevenue", "net_revenue", "netQuantity",
			"net_quantity", "profit", "profit", "sku", "sku", "name", "name"), Sort.by(Sort.Direction.DESC, "net_revenue"), List.of("product_id"));

	static final Set<String> SALE_STATUSES = Set.of("COMPLETED", "CANCELLED");

	static final Map<String, String> GROUP_UNITS = Map.of("DAY", "day", "MONTH", "month", "YEAR", "year");

	private final ReportSql sql;

	SalesReportService(ReportSql sql) {
		this.sql = sql;
	}

	// --- Liste des ventes ----------------------------------------------------------------------------

	ReportPage<SaleRow, SaleSummary> sales(ReportScope scope, PageQuery query) {
		ReportFilter f = scope.filter();
		Criteria criteria = new Criteria()
			.period("s.created_at", scope.period())
			.warehouse("s.warehouse_id", scope.warehouseId())
			.and("s.customer_id = :customerId", "customerId", f.customerId())
			.and("s.created_by = :userId", "userId", f.userId())
			.and("s.status = :status", "status", ReportSql.status(f.status(), SALE_STATUSES))
			.and("exists (select 1 from sale_line l where l.sale_id = s.id and l.product_id = :productId)", "productId",
					f.productId())
			.and("""
					exists (select 1 from sale_line l join product p on p.id = l.product_id
					        where l.sale_id = s.id and p.category_id = :categoryId)""", "categoryId", f.categoryId());
		String returns = """
				left join lateral (select sum(r.total_amount) as amount from customer_return r where r.sale_id = s.id) rt
				    on true""";
		String sales = """
				select s.id, s.number, s.created_at, s.warehouse_id, w.name as warehouse_name, s.customer_id,
				       s.customer_name, s.created_by as seller_id, %s as seller_name, s.status, s.payment_method,
				       s.total, s.cost_total, s.profit, s.paid_amount, s.credit_amount,
				       coalesce(rt.amount, 0) as returned_amount
				from sale s
				join warehouse w on w.id = s.warehouse_id
				left join app_user u on u.id = s.created_by
				%s""".formatted(ReportScope.USER_NAME, returns) + criteria.where();
		Page<SaleRow> page = this.sql.page(sales, criteria.params(), query, SALE_SORTS, (rs, i) -> {
			BigDecimal total = money(rs, "total");
			BigDecimal returned = money(rs, "returned_amount");
			return new SaleRow(uuid(rs, "id"), rs.getString("number"), instant(rs, "created_at"),
					uuid(rs, "warehouse_id"), rs.getString("warehouse_name"), uuid(rs, "customer_id"),
					rs.getString("customer_name"), uuid(rs, "seller_id"), rs.getString("seller_name"),
					rs.getString("status"), rs.getString("payment_method"), total, money(rs, "cost_total"),
					money(rs, "profit"), money(rs, "paid_amount"), money(rs, "credit_amount"), returned,
					total.subtract(returned));
		});
		SaleSummary summary = this.sql.jdbc().queryForObject("""
				select count(*) filter (where s.status = 'COMPLETED') as completed,
				       count(*) filter (where s.status = 'CANCELLED') as cancelled,
				       sum(s.total) filter (where s.status = 'COMPLETED') as total,
				       sum(s.cost_total) filter (where s.status = 'COMPLETED') as cost,
				       sum(s.profit) filter (where s.status = 'COMPLETED') as profit,
				       sum(rt.amount) filter (where s.status = 'COMPLETED') as returned,
				       sum(s.credit_amount) filter (where s.status = 'COMPLETED') as credit
				from sale s
				""" + returns + criteria.where(), criteria.params(), (rs, i) -> {
					BigDecimal total = money(rs, "total");
					BigDecimal returned = money(rs, "returned");
					return new SaleSummary(rs.getLong("completed"), rs.getLong("cancelled"), total, money(rs, "cost"),
							money(rs, "profit"), returned, total.subtract(returned), money(rs, "credit"));
				});
		return ReportPage.of(page, summary);
	}

	// --- Par produit ---------------------------------------------------------------------------------

	ReportPage<ProductSalesRow, ProductSalesSummary> byProduct(ReportScope scope, PageQuery query) {
		ReportFilter f = scope.filter();
		MapSqlParameterSource params = new MapSqlParameterSource()
			.addValue("periodStart", scope.period().start())
			.addValue("periodEnd", scope.period().end());
		String saleFilters = saleFilters(scope, params);
		StringBuilder productFilters = new StringBuilder(" where true");
		if (f.productId() != null) {
			productFilters.append(" and p.id = :productId");
			params.addValue("productId", f.productId());
		}
		if (f.categoryId() != null) {
			productFilters.append(" and p.category_id = :categoryId");
			params.addValue("categoryId", f.categoryId());
		}
		String rows = """
				with sold as (
				    select l.product_id, sum(l.quantity) as qty, sum(l.line_total) as revenue, sum(l.cost_total) as cost
				    from sale_line l
				    join sale s on s.id = l.sale_id
				    where s.status = 'COMPLETED' and s.created_at >= :periodStart and s.created_at < :periodEnd %1$s
				    group by l.product_id),
				returned as (
				    select l.product_id, sum(l.quantity) as qty, sum(l.amount) as amount,
				           coalesce(sum(l.quantity * l.unit_cost) filter (where l.item_condition = 'RESELLABLE'), 0) as cost
				    from customer_return_line l
				    join customer_return r on r.id = l.return_id
				    join sale s on s.id = r.sale_id
				    where r.created_at >= :periodStart and r.created_at < :periodEnd %1$s
				    group by l.product_id),
				keys as (select product_id from sold union select product_id from returned)
				select p.id as product_id, p.sku, p.name, c.name as category_name,
				       coalesce(so.qty, 0) as sold_quantity, coalesce(re.qty, 0) as returned_quantity,
				       coalesce(so.qty, 0) - coalesce(re.qty, 0) as net_quantity,
				       coalesce(so.revenue, 0) as revenue, coalesce(re.amount, 0) as returns,
				       coalesce(so.revenue, 0) - coalesce(re.amount, 0) as net_revenue,
				       coalesce(so.cost, 0) - coalesce(re.cost, 0) as cost,
				       (coalesce(so.revenue, 0) - coalesce(re.amount, 0)) - (coalesce(so.cost, 0) - coalesce(re.cost, 0))
				           as profit
				from keys k
				join product p on p.id = k.product_id
				join category c on c.id = p.category_id
				left join sold so on so.product_id = k.product_id
				left join returned re on re.product_id = k.product_id""".formatted(saleFilters) + productFilters;
		Page<ProductSalesRow> page = this.sql.page(rows, params, query, PRODUCT_SORTS, (rs, i) -> {
			BigDecimal netRevenue = money(rs, "net_revenue");
			BigDecimal profit = money(rs, "profit");
			return new ProductSalesRow(uuid(rs, "product_id"), rs.getString("sku"), rs.getString("name"),
					rs.getString("category_name"), rs.getLong("sold_quantity"), rs.getLong("returned_quantity"),
					rs.getLong("net_quantity"), money(rs, "revenue"), money(rs, "returns"), netRevenue,
					money(rs, "cost"), profit, rate(profit, netRevenue));
		});
		ProductSalesSummary summary = this.sql.jdbc().queryForObject("""
				select count(*) as products, coalesce(sum(net_quantity), 0) as qty, sum(net_revenue) as revenue,
				       sum(cost) as cost, sum(profit) as profit
				from (""" + rows + ") q", params,
				(rs, i) -> new ProductSalesSummary(rs.getLong("products"), rs.getLong("qty"), money(rs, "revenue"),
						money(rs, "cost"), money(rs, "profit")));
		return ReportPage.of(page, summary);
	}

	// --- Séries --------------------------------------------------------------------------------------

	/**
	 * Ventes par jour, mois ou année ; chaque période de l'intervalle est présente (0 sans vente).
	 * Remplace les rapports journalier / mensuel / annuel du front.
	 */
	List<SalesPeriodRow> byPeriod(ReportScope scope) {
		String groupBy = scope.filter().groupBy() != null ? scope.filter().groupBy().trim().toUpperCase(Locale.ROOT)
				: "DAY";
		String unit = GROUP_UNITS.get(groupBy);
		if (unit == null) {
			throw new InvalidRequestException("Paramètre invalide : groupBy vaut DAY, MONTH ou YEAR.");
		}
		if ("day".equals(unit) && scope.period().days() > 366) {
			throw new InvalidRequestException("Paramètre invalide : série journalière limitée à 366 jours.");
		}
		MapSqlParameterSource params = new MapSqlParameterSource()
			.addValue("periodStart", scope.period().start())
			.addValue("periodEnd", scope.period().end())
			.addValue("fromDate", scope.period().from())
			.addValue("toDate", scope.period().to())
			.addValue("unit", unit)
			.addValue("zone", scope.period().zone().getId());
		String saleFilters = saleFilters(scope, params);
		String sql = """
				with periods as (
				    select cast(generate_series(date_trunc(:unit, cast(:fromDate as timestamp)), cast(:toDate as timestamp),
				                                cast('1 ' || :unit as interval)) as date) as period),
				sales as (
				    select cast(date_trunc(:unit, s.created_at at time zone :zone) as date) as period, count(*) as cnt,
				           sum(s.total) as gross, sum(s.cost_total) as cost
				    from sale s
				    where s.status = 'COMPLETED' and s.created_at >= :periodStart and s.created_at < :periodEnd %1$s
				    group by 1),
				returns as (
				    select cast(date_trunc(:unit, r.created_at at time zone :zone) as date) as period,
				           sum(r.total_amount) as amount
				    from customer_return r
				    join sale s on s.id = r.sale_id
				    where r.created_at >= :periodStart and r.created_at < :periodEnd %1$s
				    group by 1),
				restocked as (
				    select cast(date_trunc(:unit, r.created_at at time zone :zone) as date) as period,
				           sum(l.quantity * l.unit_cost) as cost
				    from customer_return_line l
				    join customer_return r on r.id = l.return_id
				    join sale s on s.id = r.sale_id
				    where l.item_condition = 'RESELLABLE' and r.created_at >= :periodStart and r.created_at < :periodEnd %1$s
				    group by 1)
				select p.period, coalesce(s.cnt, 0) as cnt, coalesce(s.gross, 0) as gross, coalesce(r.amount, 0) as returns,
				       coalesce(s.cost, 0) - coalesce(k.cost, 0) as cost
				from periods p
				left join sales s on s.period = p.period
				left join returns r on r.period = p.period
				left join restocked k on k.period = p.period
				order by p.period""".formatted(saleFilters);
		return this.sql.jdbc().query(sql, params, (rs, i) -> {
			BigDecimal gross = money(rs, "gross");
			BigDecimal returns = money(rs, "returns");
			BigDecimal net = gross.subtract(returns);
			BigDecimal cost = money(rs, "cost");
			return new SalesPeriodRow(rs.getObject("period", LocalDate.class), rs.getLong("cnt"), gross, returns, net,
					cost, net.subtract(cost));
		});
	}

	// --- Par vendeur, par dépôt ----------------------------------------------------------------------

	/** Performance par vendeur (auteur de la vente) ; les retours sont imputés au vendeur de la vente. */
	List<SalesGroupRow> bySeller(ReportScope scope) {
		return grouped(scope, "s.created_by", "app_user u on u.id = g.key", ReportScope.USER_NAME);
	}

	/** Performance par dépôt. */
	List<SalesGroupRow> byWarehouse(ReportScope scope) {
		return grouped(scope, "s.warehouse_id", "warehouse u on u.id = g.key", "u.name");
	}

	private List<SalesGroupRow> grouped(ReportScope scope, String key, String join, String label) {
		MapSqlParameterSource params = new MapSqlParameterSource()
			.addValue("periodStart", scope.period().start())
			.addValue("periodEnd", scope.period().end());
		String saleFilters = saleFilters(scope, params);
		String sql = """
				with sales as (
				    select %1$s as key, count(*) as cnt, sum(s.total) as gross, sum(s.cost_total) as cost
				    from sale s
				    where s.status = 'COMPLETED' and s.created_at >= :periodStart and s.created_at < :periodEnd %3$s
				    group by 1),
				returns as (
				    select %1$s as key, sum(r.total_amount) as amount,
				           coalesce(sum((select sum(l.quantity * l.unit_cost) from customer_return_line l
				                         where l.return_id = r.id and l.item_condition = 'RESELLABLE')), 0) as cost
				    from customer_return r
				    join sale s on s.id = r.sale_id
				    where r.created_at >= :periodStart and r.created_at < :periodEnd %3$s
				    group by 1),
				g as (
				    select coalesce(s.key, r.key) as key, coalesce(s.cnt, 0) as cnt, coalesce(s.gross, 0) as gross,
				           coalesce(r.amount, 0) as returns, coalesce(s.cost, 0) - coalesce(r.cost, 0) as cost
				    from sales s
				    full join returns r on r.key = s.key)
				select g.key, %2$s as label, g.cnt, g.gross, g.returns, g.cost
				from g
				left join %4$s
				order by g.gross - g.returns desc, label""".formatted(key, label, saleFilters, join);
		return this.sql.jdbc().query(sql, params, (rs, i) -> {
			BigDecimal gross = money(rs, "gross");
			BigDecimal returns = money(rs, "returns");
			BigDecimal net = gross.subtract(returns);
			long count = rs.getLong("cnt");
			return new SalesGroupRow(uuid(rs, "key"), rs.getString("label"), count, gross, returns, net,
					net.subtract(money(rs, "cost")),
					count > 0 ? gross.divide(BigDecimal.valueOf(count), 2, java.math.RoundingMode.HALF_UP) : null);
		});
	}

	/** Filtres communs aux requêtes agrégées, sur l'alias {@code s} de la vente. */
	private static String saleFilters(ReportScope scope, MapSqlParameterSource params) {
		StringBuilder filters = new StringBuilder();
		if (scope.warehouseId() != null) {
			filters.append(" and s.warehouse_id = :warehouseId");
			params.addValue("warehouseId", scope.warehouseId());
		}
		if (scope.filter().userId() != null) {
			filters.append(" and s.created_by = :userId");
			params.addValue("userId", scope.filter().userId());
		}
		if (scope.filter().customerId() != null) {
			filters.append(" and s.customer_id = :customerId");
			params.addValue("customerId", scope.filter().customerId());
		}
		return filters.toString();
	}

}
