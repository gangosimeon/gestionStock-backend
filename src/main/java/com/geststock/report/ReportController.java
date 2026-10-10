package com.geststock.report;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springdoc.core.annotations.ParameterObject;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.common.api.PageQuery;
import com.geststock.common.security.Permissions;
import com.geststock.common.security.RequiresPermission;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.ForbiddenOperationException;
import com.geststock.report.dto.ReportFilter;
import com.geststock.report.dto.ReportPage;
import com.geststock.report.dto.ReportRows.CashSessionRow;
import com.geststock.report.dto.ReportRows.CashSummary;
import com.geststock.report.dto.ReportRows.CustomerRow;
import com.geststock.report.dto.ReportRows.CustomerSummary;
import com.geststock.report.dto.ReportRows.InventoryRow;
import com.geststock.report.dto.ReportRows.InventorySummary;
import com.geststock.report.dto.ReportRows.MovementRow;
import com.geststock.report.dto.ReportRows.MovementSummary;
import com.geststock.report.dto.ReportRows.MovementTypeRow;
import com.geststock.report.dto.ReportRows.ProductSalesRow;
import com.geststock.report.dto.ReportRows.ProductSalesSummary;
import com.geststock.report.dto.ReportRows.PurchaseOrderRow;
import com.geststock.report.dto.ReportRows.PurchaseSummary;
import com.geststock.report.dto.ReportRows.SaleRow;
import com.geststock.report.dto.ReportRows.SaleSummary;
import com.geststock.report.dto.ReportRows.SalesGroupRow;
import com.geststock.report.dto.ReportRows.SalesPeriodRow;
import com.geststock.report.dto.ReportRows.StockRow;
import com.geststock.report.dto.ReportRows.StockSummary;
import com.geststock.report.dto.ReportRows.SupplierRow;
import com.geststock.report.dto.ReportRows.SupplierSummary;
import com.geststock.warehouse.CurrentWarehouse;
import com.geststock.warehouse.WarehouseAccess;
import com.geststock.warehouse.WarehouseContext;

/**
 * Rapports, en lecture seule, calculés par PostgreSQL. Communs à tous : période {@code from} /
 * {@code to} (30 derniers jours par défaut, 10 ans au plus), dépôt courant / {@code warehouseId} /
 * {@code allWarehouses}. Les listes sont paginées ({@code page}, {@code size} ≤ 200, {@code sort})
 * avec des totaux sur tout le filtre ({@code summary}). Règles : {@code docs/reports.md}.
 */
@RestController
@RequestMapping("/api/reports")
@Tag(name = "Rapports")
@RequiresPermission(Permissions.REPORT_READ)
class ReportController {

	private final SalesReportService sales;

	private final PurchaseReportService purchases;

	private final StockReportService stock;

	private final OperationsReportService operations;

	private final DashboardService dashboard;

	private final DashboardProperties properties;

	private final WarehouseAccess warehouseAccess;

	ReportController(SalesReportService sales, PurchaseReportService purchases, StockReportService stock,
			OperationsReportService operations, DashboardService dashboard, DashboardProperties properties,
			WarehouseAccess warehouseAccess) {
		this.sales = sales;
		this.purchases = purchases;
		this.stock = stock;
		this.operations = operations;
		this.dashboard = dashboard;
		this.properties = properties;
		this.warehouseAccess = warehouseAccess;
	}

	// --- Ventes --------------------------------------------------------------------------------------

	@GetMapping("/sales")
	@Operation(summary = "Ventes", description = "Filtres : période, dépôt, customerId, userId (vendeur), status "
			+ "(COMPLETED | CANCELLED), productId, categoryId (ventes contenant ce produit / cette catégorie). "
			+ "Tri : createdAt, number, total, profit, customerName, sellerName.")
	ReportPage<SaleRow, SaleSummary> sales(@ParameterObject ReportFilter filter, @ParameterObject PageQuery page,
			@CurrentWarehouse WarehouseContext warehouse) {
		return this.sales.sales(scope(filter, warehouse), page);
	}

	@GetMapping("/sales/by-product")
	@Operation(summary = "Ventes par produit", description = "Quantités, CA et marge nets des retours de la période. "
			+ "Filtres : période, dépôt, productId, categoryId, userId, customerId. Tri : netRevenue, netQuantity, profit, "
			+ "sku, name.")
	ReportPage<ProductSalesRow, ProductSalesSummary> salesByProduct(@ParameterObject ReportFilter filter,
			@ParameterObject PageQuery page, @CurrentWarehouse WarehouseContext warehouse) {
		return this.sales.byProduct(scope(filter, warehouse), page);
	}

	@GetMapping("/sales/by-period")
	@Operation(summary = "Ventes par jour, mois ou année", description = "groupBy = DAY (366 jours au plus), MONTH, "
			+ "YEAR ; chaque période de l'intervalle est présente. Filtres : période, dépôt, userId, customerId.")
	List<SalesPeriodRow> salesByPeriod(@ParameterObject ReportFilter filter,
			@CurrentWarehouse WarehouseContext warehouse) {
		return this.sales.byPeriod(scope(filter, warehouse));
	}

	@GetMapping("/sales/by-seller")
	@Operation(summary = "Ventes par vendeur", description = "Performance des vendeurs (auteur de la vente), retours "
			+ "imputés au vendeur. Filtres : période, dépôt, customerId.")
	List<SalesGroupRow> salesBySeller(@ParameterObject ReportFilter filter,
			@CurrentWarehouse WarehouseContext warehouse) {
		return this.sales.bySeller(scope(filter, warehouse));
	}

	@GetMapping("/sales/by-warehouse")
	@Operation(summary = "Ventes par dépôt", description = "Comparaison des dépôts : utiliser allWarehouses=true. "
			+ "Filtres : période, userId, customerId.")
	List<SalesGroupRow> salesByWarehouse(@ParameterObject ReportFilter filter,
			@CurrentWarehouse WarehouseContext warehouse) {
		return this.sales.byWarehouse(scope(filter, warehouse));
	}

	// --- Achats, fournisseurs ------------------------------------------------------------------------

	@GetMapping("/purchases")
	@Operation(summary = "Commandes fournisseurs", description = "Reçu, reste à recevoir, retard. Filtres : période "
			+ "(date de commande), dépôt, supplierId, userId, status, productId, categoryId.")
	ReportPage<PurchaseOrderRow, PurchaseSummary> purchases(@ParameterObject ReportFilter filter,
			@ParameterObject PageQuery page, @CurrentWarehouse WarehouseContext warehouse) {
		return this.purchases.orders(scope(filter, warehouse), page, this.dashboard.today());
	}

	@GetMapping("/suppliers")
	@Operation(summary = "Fournisseurs", description = "Commandé et reçu sur la période, reste à recevoir et retards "
			+ "actuels, délai moyen constaté. Filtres : période, dépôt, supplierId, status (ACTIVE | INACTIVE | ALL).")
	ReportPage<SupplierRow, SupplierSummary> suppliers(@ParameterObject ReportFilter filter,
			@ParameterObject PageQuery page, @CurrentWarehouse WarehouseContext warehouse) {
		return this.purchases.suppliers(scope(filter, warehouse), page, this.dashboard.today());
	}

	// --- Stock ---------------------------------------------------------------------------------------

	@GetMapping("/stock")
	@Operation(summary = "État et valorisation du stock", description = "Par produit : quantité, valeur au dernier prix "
			+ "d'achat, ventes de la période, couverture en jours, dernière sortie. Filtres : dépôt, productId, "
			+ "categoryId, status (ACTIVE | INACTIVE | ALL), stockState (IN_STOCK | OUT_OF_STOCK | BELOW_MINIMUM | DORMANT).")
	ReportPage<StockRow, StockSummary> stock(@ParameterObject ReportFilter filter, @ParameterObject PageQuery page,
			@CurrentWarehouse WarehouseContext warehouse) {
		return this.stock.stock(scope(filter, warehouse), page);
	}

	@GetMapping("/stock-movements")
	@Operation(summary = "Mouvements de stock", description = "Filtres : période, dépôt, productId, categoryId, userId, "
			+ "type (SALE, RECEIPT, LOSS…). Totaux entrées / sorties en quantité et en valeur.")
	ReportPage<MovementRow, MovementSummary> movements(@ParameterObject ReportFilter filter,
			@ParameterObject PageQuery page, @CurrentWarehouse WarehouseContext warehouse) {
		return this.stock.movements(scope(filter, warehouse), page);
	}

	@GetMapping("/stock-movements/by-type")
	@Operation(summary = "Mouvements par type")
	List<MovementTypeRow> movementsByType(@ParameterObject ReportFilter filter,
			@CurrentWarehouse WarehouseContext warehouse) {
		return this.stock.movementsByType(scope(filter, warehouse));
	}

	// --- Inventaires, caisse, clients ----------------------------------------------------------------

	@GetMapping("/inventories")
	@Operation(summary = "Inventaires", description = "Lignes comptées, écarts en quantité et en valeur. Filtres : "
			+ "période (création), dépôt, status, userId, productId, categoryId.")
	ReportPage<InventoryRow, InventorySummary> inventories(@ParameterObject ReportFilter filter,
			@ParameterObject PageQuery page, @CurrentWarehouse WarehouseContext warehouse) {
		return this.operations.inventories(scope(filter, warehouse), page);
	}

	@GetMapping("/cash-sessions")
	@Operation(summary = "Sessions de caisse", description = "Ventes en espèces, entrées, sorties, attendu, compté, écart. "
			+ "Filtres : période (ouverture), dépôt, userId (caissier), status (OPEN | CLOSED).")
	ReportPage<CashSessionRow, CashSummary> cashSessions(@ParameterObject ReportFilter filter,
			@ParameterObject PageQuery page, @CurrentWarehouse WarehouseContext warehouse) {
		return this.operations.cashSessions(scope(filter, warehouse), page);
	}

	@GetMapping("/customers")
	@Operation(summary = "Clients et créances", description = "Solde dû, part échue, ventes et règlements de la période. "
			+ "Filtres : période, dépôt (ventes), customerId, status (ACTIVE | INACTIVE | ALL), withBalance.")
	ReportPage<CustomerRow, CustomerSummary> customers(@ParameterObject ReportFilter filter,
			@ParameterObject PageQuery page, @CurrentWarehouse WarehouseContext warehouse) {
		return this.operations.customers(scope(filter, warehouse), page);
	}

	// --- Outils --------------------------------------------------------------------------------------

	private ReportScope scope(ReportFilter filter, WarehouseContext warehouse) {
		LocalDate today = this.dashboard.today();
		ReportPeriod period = ReportPeriod.resolve(filter.from(), filter.to(), today, this.properties.timeZone());
		UUID warehouseId;
		if (Boolean.TRUE.equals(filter.allWarehouses())) {
			if (!this.warehouseAccess.seesAllWarehouses()) {
				throw new ForbiddenOperationException(ErrorCode.WAREHOUSE_ACCESS_DENIED,
						ErrorCode.WAREHOUSE_ACCESS_DENIED.defaultMessage());
			}
			warehouseId = null;
		}
		else if (filter.warehouseId() != null) {
			warehouseId = this.warehouseAccess.requireAccessible(filter.warehouseId()).id();
		}
		else {
			warehouseId = warehouse.id();
		}
		return new ReportScope(filter, period, warehouseId);
	}

}
