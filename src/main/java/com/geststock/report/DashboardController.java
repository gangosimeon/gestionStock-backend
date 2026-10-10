package com.geststock.report;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.common.security.Permissions;
import com.geststock.common.security.RequiresPermission;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.ForbiddenOperationException;
import com.geststock.report.DashboardService.Period;
import com.geststock.report.dto.DashboardResponse;
import com.geststock.report.dto.DashboardSummaryResponse;
import com.geststock.report.dto.SalesDayPoint;
import com.geststock.report.dto.StockDayPoint;
import com.geststock.report.dto.TopProductPoint;
import com.geststock.warehouse.CurrentWarehouse;
import com.geststock.warehouse.WarehouseAccess;
import com.geststock.warehouse.WarehouseContext;

/**
 * Tableau de bord : indicateurs calculés sur PostgreSQL à chaque appel. Dépôt courant par
 * défaut ({@code X-Warehouse-Id}), tous les dépôts avec {@code allWarehouses=true} et
 * {@code warehouse:all}. Dates {@code AAAA-MM-JJ} incluses, fuseau de l'entreprise.
 */
@RestController
@RequestMapping("/api/dashboard")
@Tag(name = "Tableau de bord")
@RequiresPermission(Permissions.REPORT_READ)
class DashboardController {

	private final DashboardService dashboard;

	private final WarehouseAccess warehouseAccess;

	DashboardController(DashboardService dashboard, WarehouseAccess warehouseAccess) {
		this.dashboard = dashboard;
		this.warehouseAccess = warehouseAccess;
	}

	@GetMapping
	@Operation(summary = "Tableau de bord complet",
			description = "Forme du DashboardVm du front : kpis du jour (todaySalesTotal, todayProfit, stockTotal), "
					+ "salesByDay (14 jours), topProducts (8, sur 30 jours), stockEvolution (7 jours, réelle), "
					+ "et today (indicateurs détaillés du jour).")
	DashboardResponse get(@RequestParam(required = false) Boolean allWarehouses,
			@CurrentWarehouse WarehouseContext warehouse) {
		return this.dashboard.dashboard(scope(allWarehouses, warehouse));
	}

	@GetMapping("/summary")
	@Operation(summary = "Indicateurs d'une période",
			description = "Ventes (CA brut, retours, CA net, coût, marge brute, taux de marge, panier moyen, annulations), "
					+ "achats (réceptions, commandes) et stock (unités, valeur, ruptures, seuils). Par défaut : aujourd'hui.")
	DashboardSummaryResponse summary(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) Boolean allWarehouses, @CurrentWarehouse WarehouseContext warehouse) {
		LocalDate today = this.dashboard.today();
		return this.dashboard.summary(Period.resolve(from, to, null, 1, today), scope(allWarehouses, warehouse));
	}

	@GetMapping("/sales-by-day")
	@Operation(summary = "Évolution des ventes", description = "Un point par jour (0 les jours sans vente). Par défaut : "
			+ "14 derniers jours ; days ou from/to, 366 jours au plus.")
	List<SalesDayPoint> salesByDay(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) Integer days, @RequestParam(required = false) Boolean allWarehouses,
			@CurrentWarehouse WarehouseContext warehouse) {
		return this.dashboard.salesByDay(Period.resolve(from, to, days, 14, this.dashboard.today()),
				scope(allWarehouses, warehouse));
	}

	@GetMapping("/top-products")
	@Operation(summary = "Produits les plus vendus",
			description = "Par chiffre d'affaires net décroissant. Par défaut : 8 produits sur les 30 derniers jours.")
	List<TopProductPoint> topProducts(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) Integer days,
			@Parameter(description = "1 à 50") @RequestParam(defaultValue = "8") int limit,
			@RequestParam(required = false) Boolean allWarehouses, @CurrentWarehouse WarehouseContext warehouse) {
		return this.dashboard.topProducts(Period.resolve(from, to, days, 30, this.dashboard.today()), limit,
				scope(allWarehouses, warehouse));
	}

	@GetMapping("/stock-evolution")
	@Operation(summary = "Évolution du stock",
			description = "Quantités en stock en fin de journée, reconstituées depuis le journal des mouvements. "
					+ "Par défaut : 7 derniers jours ; aujourd'hui = stock actuel.")
	List<StockDayPoint> stockEvolution(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) Integer days, @RequestParam(required = false) Boolean allWarehouses,
			@CurrentWarehouse WarehouseContext warehouse) {
		return this.dashboard.stockEvolution(Period.resolve(from, to, days, 7, this.dashboard.today()),
				scope(allWarehouses, warehouse));
	}

	private UUID scope(Boolean allWarehouses, WarehouseContext warehouse) {
		if (Boolean.TRUE.equals(allWarehouses)) {
			if (!this.warehouseAccess.seesAllWarehouses()) {
				throw new ForbiddenOperationException(ErrorCode.WAREHOUSE_ACCESS_DENIED,
						ErrorCode.WAREHOUSE_ACCESS_DENIED.defaultMessage());
			}
			return null;
		}
		return warehouse.id();
	}

}
