package com.geststock.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
import com.geststock.report.dto.ReportRows.MovementRow;
import com.geststock.report.dto.ReportRows.MovementSummary;
import com.geststock.report.dto.ReportRows.MovementTypeRow;
import com.geststock.report.dto.ReportRows.StockRow;
import com.geststock.report.dto.ReportRows.StockSummary;
import com.geststock.stock.StockMovementType;

import static com.geststock.report.ReportScope.instant;
import static com.geststock.report.ReportScope.money;
import static com.geststock.report.ReportScope.uuid;

/**
 * Rapports de stock : état et valorisation par produit (rotation, couverture, dormance),
 * journal des mouvements et synthèse par type de mouvement.
 */
@Service
@Transactional(readOnly = true)
class StockReportService {

	static final SortWhitelist STOCK_SORTS = new SortWhitelist(Map.of("value", "value", "quantity", "quantity", "sku",
			"sku", "name", "name", "soldQuantity", "sold_quantity", "coverageDays", "coverage_days", "lastExitAt",
			"last_exit_at"), Sort.by(Sort.Direction.DESC, "value"), List.of("product_id"));

	static final SortWhitelist MOVEMENT_SORTS = new SortWhitelist(Map.of("createdAt", "created_at", "quantity",
			"quantity", "type", "type", "sku", "sku"), Sort.by(Sort.Direction.DESC, "created_at"));

	static final Set<String> ACTIVITY = Set.of("ACTIVE", "INACTIVE", "ALL");

	static final Set<String> STOCK_STATES = Set.of("IN_STOCK", "OUT_OF_STOCK", "BELOW_MINIMUM", "DORMANT");

	static final Set<String> MOVEMENT_TYPES = Arrays.stream(StockMovementType.values())
		.map(Enum::name)
		.collect(Collectors.toUnmodifiableSet());

	private final ReportSql sql;

	StockReportService(ReportSql sql) {
		this.sql = sql;
	}

	// --- État et valorisation ------------------------------------------------------------------------

	/**
	 * Un produit par ligne, quantités du dépôt (ou de tous) ; valeur au dernier prix d'achat ;
	 * ventes nettes de la période (sorties de vente − annulations − retours clients).
	 */
	ReportPage<StockRow, StockSummary> stock(ReportScope scope, PageQuery query) {
		ReportFilter f = scope.filter();
		MapSqlParameterSource params = new MapSqlParameterSource()
			.addValue("periodStart", scope.period().start())
			.addValue("periodEnd", scope.period().end())
			.addValue("periodDays", scope.period().days());
		String levelScope = "";
		String movementScope = "";
		if (scope.warehouseId() != null) {
			levelScope = " and l.warehouse_id = :warehouseId";
			movementScope = " and m.warehouse_id = :warehouseId";
			params.addValue("warehouseId", scope.warehouseId());
		}
		StringBuilder productWhere = new StringBuilder(" where true");
		if (f.productId() != null) {
			productWhere.append(" and p.id = :productId");
			params.addValue("productId", f.productId());
		}
		if (f.categoryId() != null) {
			productWhere.append(" and p.category_id = :categoryId");
			params.addValue("categoryId", f.categoryId());
		}
		String activity = ReportSql.status(f.status(), ACTIVITY);
		if ("ACTIVE".equals(activity)) {
			productWhere.append(" and p.active");
		}
		else if ("INACTIVE".equals(activity)) {
			productWhere.append(" and not p.active");
		}
		String products = """
				select p.id as product_id, p.sku, p.name, c.name as category_name, p.active, p.alert_threshold,
				       p.max_stock_level, p.purchase_price as unit_cost, coalesce(st.qty, 0) as quantity,
				       greatest(coalesce(st.qty, 0), 0) * p.purchase_price as value,
				       coalesce(sold.qty, 0) as sold_quantity,
				       case when coalesce(sold.qty, 0) > 0
				            then round(coalesce(st.qty, 0) * :periodDays / cast(sold.qty as numeric), 1) end as coverage_days,
				       mv.last_exit_at, mv.first_movement_at
				from product p
				join category c on c.id = p.category_id
				left join lateral (
				    select sum(l.quantity) as qty from stock_level l where l.product_id = p.id %1$s) st on true
				left join lateral (
				    select -sum(m.quantity) as qty from stock_movement m
				    where m.product_id = p.id and m.type in ('SALE', 'SALE_CANCELLATION', 'CUSTOMER_RETURN')
				      and m.created_at >= :periodStart and m.created_at < :periodEnd %2$s) sold on true
				left join lateral (
				    select max(m.created_at) filter (where m.type in ('SALE', 'TRANSFER_OUT')) as last_exit_at,
				           min(m.created_at) as first_movement_at
				    from stock_movement m where m.product_id = p.id %2$s) mv on true""".formatted(levelScope, movementScope)
				+ productWhere;
		String state = ReportSql.status(f.stockState(), STOCK_STATES);
		String stateWhere = state == null ? "" : switch (state) {
			case "IN_STOCK" -> " where quantity > 0";
			case "OUT_OF_STOCK" -> " where quantity <= 0";
			case "BELOW_MINIMUM" -> """
					 where quantity > 0 and quantity <= case when alert_threshold > 0 then alert_threshold
					     else (select s.default_min_stock from alert_setting s limit 1) end""";
			default -> """
					 where quantity > 0 and (select s.dormant_stock_days from alert_setting s limit 1) > 0
					   and coalesce(last_exit_at, first_movement_at)
					       < now() - make_interval(days => (select s.dormant_stock_days from alert_setting s limit 1))""";
		};
		// Espace explicite : un bloc de texte perd l'indentation de sa première ligne.
		String rows = "select * from (" + products + ") x " + stateWhere;
		Page<StockRow> page = this.sql.page(rows, params, query, STOCK_SORTS,
				(rs, i) -> new StockRow(uuid(rs, "product_id"), rs.getString("sku"), rs.getString("name"),
						rs.getString("category_name"), rs.getBoolean("active"), rs.getLong("quantity"),
						money(rs, "unit_cost"), money(rs, "value"), rs.getInt("alert_threshold"),
						(Integer) rs.getObject("max_stock_level"), rs.getLong("sold_quantity"),
						rs.getBigDecimal("coverage_days"), instant(rs, "last_exit_at")));
		StockSummary summary = this.sql.jdbc().queryForObject("""
				select count(*) as products, coalesce(sum(quantity), 0) as units, sum(value) as value,
				       count(*) filter (where active and quantity <= 0) as out_of_stock
				from (""" + rows + ") q", params,
				(rs, i) -> new StockSummary(rs.getLong("products"), rs.getLong("units"), money(rs, "value"),
						rs.getLong("out_of_stock")));
		return ReportPage.of(page, summary);
	}

	// --- Mouvements ----------------------------------------------------------------------------------

	ReportPage<MovementRow, MovementSummary> movements(ReportScope scope, PageQuery query) {
		Criteria criteria = movementCriteria(scope, true);
		String rows = """
				select m.id, m.created_at, m.warehouse_id, w.name as warehouse_name, m.product_id, p.sku,
				       p.name as product_name, m.type, m.quantity, m.balance_after, m.unit_cost,
				       m.quantity * m.unit_cost as value, m.reason, m.source_type, m.source_reference,
				       m.created_by as user_id, %s as user_name
				from stock_movement m
				join product p on p.id = m.product_id
				join warehouse w on w.id = m.warehouse_id
				left join app_user u on u.id = m.created_by""".formatted(ReportScope.USER_NAME) + criteria.where();
		Page<MovementRow> page = this.sql.page(rows, criteria.params(), query, MOVEMENT_SORTS, (rs, i) -> {
			BigDecimal unitCost = rs.getBigDecimal("unit_cost");
			BigDecimal value = rs.getBigDecimal("value");
			return new MovementRow(uuid(rs, "id"), instant(rs, "created_at"), uuid(rs, "warehouse_id"),
					rs.getString("warehouse_name"), uuid(rs, "product_id"), rs.getString("sku"),
					rs.getString("product_name"), rs.getString("type"), rs.getInt("quantity"), rs.getInt("balance_after"),
					unitCost != null ? unitCost.setScale(2, RoundingMode.HALF_UP) : null,
					value != null ? value.setScale(2, RoundingMode.HALF_UP) : null, rs.getString("reason"),
					rs.getString("source_type"), rs.getString("source_reference"), uuid(rs, "user_id"),
					rs.getString("user_name"));
		});
		MovementSummary summary = this.sql.jdbc().queryForObject("""
				select count(*) as cnt,
				       coalesce(sum(m.quantity) filter (where m.quantity > 0), 0) as qty_in,
				       coalesce(-sum(m.quantity) filter (where m.quantity < 0), 0) as qty_out,
				       sum(m.quantity * m.unit_cost) filter (where m.quantity > 0) as value_in,
				       -sum(m.quantity * m.unit_cost) filter (where m.quantity < 0) as value_out
				from stock_movement m
				join product p on p.id = m.product_id""" + criteria.where(), criteria.params(),
				(rs, i) -> new MovementSummary(rs.getLong("cnt"), rs.getLong("qty_in"), rs.getLong("qty_out"),
						money(rs, "value_in"), money(rs, "value_out")));
		return ReportPage.of(page, summary);
	}

	/** Mouvements regroupés par type (entrées, sorties, ajustements…). */
	List<MovementTypeRow> movementsByType(ReportScope scope) {
		Criteria criteria = movementCriteria(scope, false);
		return this.sql.jdbc().query("""
				select m.type, count(*) as cnt, sum(m.quantity) as qty, sum(m.quantity * m.unit_cost) as value
				from stock_movement m
				join product p on p.id = m.product_id""" + criteria.where() + " group by m.type order by m.type",
				criteria.params(),
				(rs, i) -> new MovementTypeRow(rs.getString("type"), rs.getLong("cnt"), rs.getLong("qty"),
						money(rs, "value")));
	}

	private static Criteria movementCriteria(ReportScope scope, boolean withType) {
		ReportFilter f = scope.filter();
		return new Criteria()
			.period("m.created_at", scope.period())
			.warehouse("m.warehouse_id", scope.warehouseId())
			.and("m.product_id = :productId", "productId", f.productId())
			.and("p.category_id = :categoryId", "categoryId", f.categoryId())
			.and("m.created_by = :userId", "userId", f.userId())
			.and("m.type = :type", "type", withType ? ReportSql.status(f.type(), MOVEMENT_TYPES) : null);
	}

}
