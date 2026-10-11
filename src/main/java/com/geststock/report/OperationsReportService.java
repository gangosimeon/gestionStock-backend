package com.geststock.report;

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
import com.geststock.report.dto.ReportRows.CashSessionRow;
import com.geststock.report.dto.ReportRows.CashSummary;
import com.geststock.report.dto.ReportRows.CustomerRow;
import com.geststock.report.dto.ReportRows.CustomerSummary;
import com.geststock.report.dto.ReportRows.InventoryRow;
import com.geststock.report.dto.ReportRows.InventorySummary;

import static com.geststock.report.ReportScope.instant;
import static com.geststock.report.ReportScope.money;
import static com.geststock.report.ReportScope.uuid;

/** Rapports d'exploitation : inventaires, sessions de caisse, clients (créances et activité). */
@Service
@Transactional(readOnly = true)
class OperationsReportService {

	static final SortWhitelist INVENTORY_SORTS = new SortWhitelist(Map.of("createdAt", "created_at", "validatedAt",
			"validated_at", "number", "number", "valueDifference", "value_difference"),
			Sort.by(Sort.Direction.DESC, "created_at"));

	static final SortWhitelist CASH_SORTS = new SortWhitelist(Map.of("openedAt", "opened_at", "difference",
			"difference", "cashierName", "cashier_name", "cashSales", "cash_sales"),
			Sort.by(Sort.Direction.DESC, "opened_at"));

	static final SortWhitelist CUSTOMER_SORTS = new SortWhitelist(Map.of("balance", "balance", "overdueAmount",
			"overdue_amount", "name", "name", "code", "code", "salesAmount", "sales_amount", "lastSaleAt", "last_sale_at"),
			Sort.by(Sort.Direction.DESC, "balance"), List.of("customer_id"));

	static final Set<String> INVENTORY_STATUSES = Set.of("DRAFT", "IN_PROGRESS", "COUNTING", "VALIDATION", "COMPLETED",
			"CANCELLED");

	static final Set<String> CASH_STATUSES = Set.of("OPEN", "CLOSED");

	static final Set<String> ACTIVITY = Set.of("ACTIVE", "INACTIVE", "ALL");

	private final ReportSql sql;

	OperationsReportService(ReportSql sql) {
		this.sql = sql;
	}

	// --- Inventaires ---------------------------------------------------------------------------------

	/** Inventaires de la période (date de création) ; écart valorisé au coût des mouvements appliqués. */
	ReportPage<InventoryRow, InventorySummary> inventories(ReportScope scope, PageQuery query) {
		ReportFilter f = scope.filter();
		Criteria criteria = new Criteria()
			.period("i.created_at", scope.period())
			.warehouse("i.warehouse_id", scope.warehouseId())
			.and("i.status = :status", "status", ReportSql.status(f.status(), INVENTORY_STATUSES))
			.and("(i.created_by = :userId or i.started_by = :userId or i.validated_by = :userId)", "userId", f.userId())
			.and("exists (select 1 from inventory_line l where l.inventory_id = i.id and l.product_id = :productId)",
					"productId", f.productId())
			.and("""
					exists (select 1 from inventory_line l join product p on p.id = l.product_id
					        where l.inventory_id = i.id and p.category_id = :categoryId)""", "categoryId", f.categoryId());
		String rows = """
				select i.id, i.number, i.warehouse_id, w.name as warehouse_name, i.status, i.scope, i.created_at,
				       i.validated_at, %s as validated_by_name, coalesce(ln.lines, 0) as lines,
				       coalesce(ln.counted, 0) as counted_lines, coalesce(ln.gaps, 0) as gap_lines,
				       coalesce(ln.gain, 0) as quantity_gain, coalesce(ln.loss, 0) as quantity_loss,
				       coalesce(ln.value, 0) as value_difference
				from inventory_session i
				join warehouse w on w.id = i.warehouse_id
				left join app_user u on u.id = i.validated_by
				left join lateral (
				    select count(*) as lines, count(*) filter (where l.counted_quantity is not null) as counted,
				           count(*) filter (where l.difference <> 0) as gaps,
				           sum(l.difference) filter (where l.difference > 0) as gain,
				           -sum(l.difference) filter (where l.difference < 0) as loss,
				           sum(m.quantity * m.unit_cost) as value
				    from inventory_line l
				    left join stock_movement m on m.id = l.stock_movement_id
				    where l.inventory_id = i.id) ln on true""".formatted(ReportScope.USER_NAME) + criteria.where();
		Page<InventoryRow> page = this.sql.page(rows, criteria.params(), query, INVENTORY_SORTS,
				(rs, i) -> new InventoryRow(uuid(rs, "id"), rs.getString("number"), uuid(rs, "warehouse_id"),
						rs.getString("warehouse_name"), rs.getString("status"), rs.getString("scope"),
						instant(rs, "created_at"), instant(rs, "validated_at"), rs.getString("validated_by_name"),
						rs.getLong("lines"), rs.getLong("counted_lines"), rs.getLong("gap_lines"),
						rs.getLong("quantity_gain"), rs.getLong("quantity_loss"), money(rs, "value_difference")));
		InventorySummary summary = this.sql.jdbc().queryForObject("""
				select count(*) as cnt, count(*) filter (where status = 'COMPLETED') as completed,
				       coalesce(sum(quantity_gain), 0) as gain, coalesce(sum(quantity_loss), 0) as loss,
				       sum(value_difference) as value
				from (""" + rows + ") q", criteria.params(),
				(rs, i) -> new InventorySummary(rs.getLong("cnt"), rs.getLong("completed"), rs.getLong("gain"),
						rs.getLong("loss"), money(rs, "value")));
		return ReportPage.of(page, summary);
	}

	// --- Caisse --------------------------------------------------------------------------------------

	/**
	 * Sessions ouvertes pendant la période. Espèces calculées depuis les écritures (mêmes règles
	 * que le récapitulatif de caisse) ; comptage et écart figés à la clôture.
	 */
	ReportPage<CashSessionRow, CashSummary> cashSessions(ReportScope scope, PageQuery query) {
		ReportFilter f = scope.filter();
		Criteria criteria = new Criteria()
			.period("cs.opened_at", scope.period())
			.warehouse("cs.warehouse_id", scope.warehouseId())
			.and("cs.cashier_id = :userId", "userId", f.userId())
			.and("cs.status = :status", "status", ReportSql.status(f.status(), CASH_STATUSES));
		String rows = """
				select cs.id, cs.number, cs.warehouse_id, w.name as warehouse_name, cs.cashier_id, %s as cashier_name,
				       cs.terminal, cs.status, cs.opened_at, cs.closed_at, cs.opening_balance, e.cash_sales, e.cash_in,
				       e.cash_out, e.non_cash, cs.opening_balance + e.cash_sales + e.cash_in - e.cash_out as expected_cash,
				       cs.counted_cash, cs.difference
				from cash_session cs
				join warehouse w on w.id = cs.warehouse_id
				left join app_user u on u.id = cs.cashier_id
				left join lateral (
				    select coalesce(sum(case when ce.payment_method = 'CASH' and ce.entry_type = 'SALE' then ce.amount
				                             when ce.payment_method = 'CASH'
				                                  and ce.entry_type in ('SALE_REFUND', 'RETURN_REFUND') then -ce.amount
				                             else 0 end), 0) as cash_sales,
				           coalesce(sum(case when ce.payment_method = 'CASH'
				                                  and ce.entry_type in ('CUSTOMER_PAYMENT', 'CASH_IN') then ce.amount
				                             when ce.payment_method = 'CASH' and ce.entry_type = 'CORRECTION_OUT' then -ce.amount
				                             else 0 end), 0) as cash_in,
				           coalesce(sum(case when ce.payment_method = 'CASH'
				                                  and ce.entry_type in ('CASH_OUT', 'SUPPLIER_PAYMENT', 'EXPENSE') then ce.amount
				                             when ce.payment_method = 'CASH'
				                                  and ce.entry_type in ('CORRECTION_IN', 'EXPENSE_CANCELLATION') then -ce.amount
				                             else 0 end), 0) as cash_out,
				           coalesce(sum(case when ce.payment_method <> 'CASH'
				                             then case when ce.direction = 'IN' then ce.amount else -ce.amount end
				                             else 0 end), 0) as non_cash
				    from cash_entry ce
				    where ce.session_id = cs.id) e on true""".formatted(ReportScope.USER_NAME) + criteria.where();
		Page<CashSessionRow> page = this.sql.page(rows, criteria.params(), query, CASH_SORTS,
				(rs, i) -> new CashSessionRow(uuid(rs, "id"), rs.getString("number"), uuid(rs, "warehouse_id"),
						rs.getString("warehouse_name"), uuid(rs, "cashier_id"), rs.getString("cashier_name"),
						rs.getString("terminal"), rs.getString("status"), instant(rs, "opened_at"),
						instant(rs, "closed_at"), money(rs, "opening_balance"), money(rs, "cash_sales"),
						money(rs, "cash_in"), money(rs, "cash_out"), money(rs, "non_cash"), money(rs, "expected_cash"),
						rs.getBigDecimal("counted_cash"), rs.getBigDecimal("difference")));
		CashSummary summary = this.sql.jdbc().queryForObject("""
				select count(*) as cnt, count(*) filter (where status = 'OPEN') as open, sum(cash_sales) as cash_sales,
				       sum(non_cash) as non_cash, sum(difference) as difference,
				       count(*) filter (where difference <> 0) as with_difference
				from (""" + rows + ") q", criteria.params(),
				(rs, i) -> new CashSummary(rs.getLong("cnt"), rs.getLong("open"), money(rs, "cash_sales"),
						money(rs, "non_cash"), money(rs, "difference"), rs.getLong("with_difference")));
		return ReportPage.of(page, summary);
	}

	// --- Clients -------------------------------------------------------------------------------------

	/**
	 * Clients : créance actuelle (vue {@code v_customer_receivable}, tous dépôts) et activité de
	 * la période (ventes validées du dépôt, règlements).
	 */
	ReportPage<CustomerRow, CustomerSummary> customers(ReportScope scope, PageQuery query) {
		ReportFilter f = scope.filter();
		MapSqlParameterSource params = new MapSqlParameterSource()
			.addValue("periodStart", scope.period().start())
			.addValue("periodEnd", scope.period().end());
		String saleScope = "";
		if (scope.warehouseId() != null) {
			saleScope = " and s.warehouse_id = :warehouseId";
			params.addValue("warehouseId", scope.warehouseId());
		}
		StringBuilder where = new StringBuilder(" where true");
		if (f.customerId() != null) {
			where.append(" and c.id = :customerId");
			params.addValue("customerId", f.customerId());
		}
		String activity = ReportSql.status(f.status(), ACTIVITY);
		if ("ACTIVE".equals(activity)) {
			where.append(" and c.active");
		}
		else if ("INACTIVE".equals(activity)) {
			where.append(" and not c.active");
		}
		if (Boolean.TRUE.equals(f.withBalance())) {
			where.append(" and v.balance > 0");
		}
		String rows = """
				select c.id as customer_id, c.code, c.name, c.active, c.credit_limit, v.payment_terms_days, v.balance,
				       v.overdue_amount, v.oldest_due_at, coalesce(sa.cnt, 0) as sales_count,
				       coalesce(sa.amount, 0) as sales_amount, coalesce(pa.amount, 0) as payments_amount, ls.last_sale_at
				from customer c
				join v_customer_receivable v on v.customer_id = c.id
				left join lateral (
				    select count(*) as cnt, sum(s.total) as amount from sale s
				    where s.customer_id = c.id and s.status = 'COMPLETED'
				      and s.created_at >= :periodStart and s.created_at < :periodEnd %1$s) sa on true
				left join lateral (
				    select sum(e.amount) as amount from customer_account_entry e
				    where e.customer_id = c.id and e.entry_type = 'PAYMENT'
				      and e.created_at >= :periodStart and e.created_at < :periodEnd) pa on true
				left join lateral (
				    select max(s.created_at) as last_sale_at from sale s
				    where s.customer_id = c.id and s.status = 'COMPLETED' %1$s) ls on true""".formatted(saleScope) + where;
		Page<CustomerRow> page = this.sql.page(rows, params, query, CUSTOMER_SORTS,
				(rs, i) -> new CustomerRow(uuid(rs, "customer_id"), rs.getString("code"), rs.getString("name"),
						rs.getBoolean("active"), money(rs, "credit_limit"), rs.getInt("payment_terms_days"),
						money(rs, "balance"), money(rs, "overdue_amount"), instant(rs, "oldest_due_at"),
						rs.getLong("sales_count"), money(rs, "sales_amount"), money(rs, "payments_amount"),
						instant(rs, "last_sale_at")));
		CustomerSummary summary = this.sql.jdbc().queryForObject("""
				select count(*) as cnt, count(*) filter (where balance > 0) as debtors, sum(balance) as balance,
				       sum(overdue_amount) as overdue, sum(sales_amount) as sales, sum(payments_amount) as payments
				from (""" + rows + ") q", params,
				(rs, i) -> new CustomerSummary(rs.getLong("cnt"), rs.getLong("debtors"), money(rs, "balance"),
						money(rs, "overdue"), money(rs, "sales"), money(rs, "payments")));
		return ReportPage.of(page, summary);
	}

}
