package com.geststock.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.geststock.common.api.PageQuery;
import com.geststock.common.api.SortWhitelist;
import com.geststock.report.ReportSql.Criteria;
import com.geststock.report.dto.ReportFilter;
import com.geststock.report.dto.ReportPage;
import com.geststock.report.dto.ReportRows.PurchaseOrderRow;
import com.geststock.report.dto.ReportRows.PurchaseSummary;
import com.geststock.report.dto.ReportRows.SupplierRow;
import com.geststock.report.dto.ReportRows.SupplierSummary;

import static com.geststock.report.ReportScope.instant;
import static com.geststock.report.ReportScope.money;
import static com.geststock.report.ReportScope.uuid;

/**
 * Rapports d'achats : commandes fournisseurs (reçu, reste à recevoir, retards) et synthèse par
 * fournisseur. Les factures et règlements fournisseurs ne sont pas gérés : pas de dette
 * fournisseur, seulement le reste à recevoir.
 */
@Service
@Transactional(readOnly = true)
class PurchaseReportService {

	static final SortWhitelist ORDER_SORTS = new SortWhitelist(Map.of("createdAt", "created_at", "number", "number",
			"supplierName", "supplier_name", "totalAmount", "total_amount", "remainingAmount", "remaining_amount",
			"expectedDeliveryDate", "expected_delivery_date"), Sort.by(Sort.Direction.DESC, "created_at"));

	static final SortWhitelist SUPPLIER_SORTS = new SortWhitelist(Map.of("orderedAmount", "ordered_amount",
			"receivedAmount", "received_amount", "openRemainingAmount", "open_remaining_amount", "name", "name",
			"code", "code"), Sort.by(Sort.Direction.DESC, "ordered_amount"), List.of("supplier_id"));

	static final Set<String> ORDER_STATUSES = Set.of("ORDERED", "PARTIALLY_RECEIVED", "RECEIVED", "CLOSED",
			"CANCELLED");

	static final Set<String> ACTIVITY = Set.of("ACTIVE", "INACTIVE", "ALL");

	private final ReportSql sql;

	PurchaseReportService(ReportSql sql) {
		this.sql = sql;
	}

	ReportPage<PurchaseOrderRow, PurchaseSummary> orders(ReportScope scope, PageQuery query, LocalDate today) {
		ReportFilter f = scope.filter();
		Criteria criteria = new Criteria()
			.param("today", today)
			.period("o.created_at", scope.period())
			.warehouse("o.warehouse_id", scope.warehouseId())
			.and("o.supplier_id = :supplierId", "supplierId", f.supplierId())
			.and("o.created_by = :userId", "userId", f.userId())
			.and("o.status = :status", "status", ReportSql.status(f.status(), ORDER_STATUSES))
			.and("exists (select 1 from purchase_order_line l where l.purchase_order_id = o.id and l.product_id = :productId)",
					"productId", f.productId())
			.and("""
					exists (select 1 from purchase_order_line l join product p on p.id = l.product_id
					        where l.purchase_order_id = o.id and p.category_id = :categoryId)""", "categoryId",
					f.categoryId());
		String rows = """
				select o.id, o.number, o.created_at, o.supplier_id, o.supplier_name, o.warehouse_id,
				       w.name as warehouse_name, o.status, o.expected_delivery_date, o.last_receipt_at, o.total_amount,
				       o.received_amount,
				       case when o.status in ('ORDERED', 'PARTIALLY_RECEIVED') then o.total_amount - o.received_amount
				            else 0 end as remaining_amount,
				       (o.status in ('ORDERED', 'PARTIALLY_RECEIVED') and o.expected_delivery_date < :today) as overdue,
				       %s as created_by_name
				from purchase_order o
				join warehouse w on w.id = o.warehouse_id
				left join app_user u on u.id = o.created_by""".formatted(ReportScope.USER_NAME) + criteria.where();
		Page<PurchaseOrderRow> page = this.sql.page(rows, criteria.params(), query, ORDER_SORTS,
				(rs, i) -> new PurchaseOrderRow(uuid(rs, "id"), rs.getString("number"), instant(rs, "created_at"),
						uuid(rs, "supplier_id"), rs.getString("supplier_name"), uuid(rs, "warehouse_id"),
						rs.getString("warehouse_name"), rs.getString("status"),
						rs.getObject("expected_delivery_date", LocalDate.class), instant(rs, "last_receipt_at"),
						money(rs, "total_amount"), money(rs, "received_amount"), money(rs, "remaining_amount"),
						rs.getBoolean("overdue"), rs.getString("created_by_name")));
		PurchaseSummary summary = this.sql.jdbc().queryForObject("""
				select count(*) as cnt, sum(total_amount) filter (where status <> 'CANCELLED') as total,
				       sum(received_amount) as received, sum(remaining_amount) as remaining,
				       count(*) filter (where overdue) as overdue
				from (""" + rows + ") q", criteria.params(),
				(rs, i) -> new PurchaseSummary(rs.getLong("cnt"), money(rs, "total"), money(rs, "received"),
						money(rs, "remaining"), rs.getLong("overdue")));
		return ReportPage.of(page, summary);
	}

	/**
	 * Synthèse par fournisseur : commandes passées et réceptions de la période, reste à recevoir
	 * et retards actuels (toutes commandes ouvertes), délai moyen constaté.
	 */
	ReportPage<SupplierRow, SupplierSummary> suppliers(ReportScope scope, PageQuery query, LocalDate today) {
		ReportFilter f = scope.filter();
		MapSqlParameterSource params = new MapSqlParameterSource()
			.addValue("periodStart", scope.period().start())
			.addValue("periodEnd", scope.period().end())
			.addValue("today", today);
		String orderScope = "";
		String receiptScope = "";
		if (scope.warehouseId() != null) {
			orderScope = " and o.warehouse_id = :warehouseId";
			receiptScope = " and g.warehouse_id = :warehouseId";
			params.addValue("warehouseId", scope.warehouseId());
		}
		StringBuilder where = new StringBuilder(" where true");
		if (f.supplierId() != null) {
			where.append(" and sp.id = :supplierId");
			params.addValue("supplierId", f.supplierId());
		}
		String activity = ReportSql.status(f.status(), ACTIVITY);
		if ("ACTIVE".equals(activity)) {
			where.append(" and sp.active");
		}
		else if ("INACTIVE".equals(activity)) {
			where.append(" and not sp.active");
		}
		String rows = """
				select sp.id as supplier_id, sp.code, sp.name, sp.active, sp.delivery_lead_time_days,
				       coalesce(ord.cnt, 0) as orders_count, coalesce(ord.amount, 0) as ordered_amount,
				       ord.avg_delay as average_delivery_days,
				       coalesce(rec.cnt, 0) as receipts_count, coalesce(rec.amount, 0) as received_amount,
				       coalesce(op.remaining, 0) as open_remaining_amount, coalesce(op.overdue, 0) as overdue_orders,
				       lr.last_receipt_at
				from supplier sp
				left join lateral (
				    select count(*) as cnt, sum(o.total_amount) as amount,
				           avg(extract(epoch from (o.last_receipt_at - o.created_at)) / 86400)
				               filter (where o.status = 'RECEIVED') as avg_delay
				    from purchase_order o
				    where o.supplier_id = sp.id and o.status <> 'CANCELLED'
				      and o.created_at >= :periodStart and o.created_at < :periodEnd %1$s) ord on true
				left join lateral (
				    select count(*) as cnt, sum(g.total_amount) as amount
				    from goods_receipt g
				    join purchase_order o on o.id = g.purchase_order_id
				    where o.supplier_id = sp.id and g.received_at >= :periodStart and g.received_at < :periodEnd %2$s) rec on true
				left join lateral (
				    select sum(o.total_amount - o.received_amount) as remaining,
				           count(*) filter (where o.expected_delivery_date < :today) as overdue
				    from purchase_order o
				    where o.supplier_id = sp.id and o.status in ('ORDERED', 'PARTIALLY_RECEIVED') %1$s) op on true
				left join lateral (
				    select max(g.received_at) as last_receipt_at
				    from goods_receipt g
				    join purchase_order o on o.id = g.purchase_order_id
				    where o.supplier_id = sp.id %2$s) lr on true""".formatted(orderScope, receiptScope) + where;
		Page<SupplierRow> page = this.sql.page(rows, params, query, SUPPLIER_SORTS, (rs, i) -> {
			BigDecimal delay = rs.getBigDecimal("average_delivery_days");
			return new SupplierRow(uuid(rs, "supplier_id"), rs.getString("code"), rs.getString("name"),
					rs.getBoolean("active"), rs.getLong("orders_count"), money(rs, "ordered_amount"),
					rs.getLong("receipts_count"), money(rs, "received_amount"), money(rs, "open_remaining_amount"),
					rs.getLong("overdue_orders"), instant(rs, "last_receipt_at"),
					delay != null ? delay.setScale(1, RoundingMode.HALF_UP) : null, rs.getInt("delivery_lead_time_days"));
		});
		SupplierSummary summary = this.sql.jdbc().queryForObject("""
				select count(*) as cnt, sum(ordered_amount) as ordered, sum(received_amount) as received,
				       sum(open_remaining_amount) as remaining
				from (""" + rows + ") q", params,
				(rs, i) -> new SupplierSummary(rs.getLong("cnt"), money(rs, "ordered"), money(rs, "received"),
						money(rs, "remaining")));
		return ReportPage.of(page, summary);
	}

}
