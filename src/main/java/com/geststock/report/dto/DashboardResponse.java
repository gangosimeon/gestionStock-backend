package com.geststock.report.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Tableau de bord en un appel, à la forme du {@code DashboardVm} du front : indicateurs du jour,
 * ventes des 14 derniers jours, top 8 produits des 30 derniers jours, stock des 7 derniers jours.
 */
@Schema(name = "Dashboard")
public record DashboardResponse(Instant generatedAt, UUID warehouseId, Kpis kpis, List<SalesDayPoint> salesByDay,
		List<TopProductPoint> topProducts, List<StockDayPoint> stockEvolution, DashboardSummaryResponse today) {

	/**
	 * Cartes du front.
	 *
	 * @param todaySalesTotal chiffre d'affaires net du jour
	 * @param todayProfit     marge brute du jour
	 * @param stockTotal      quantités en stock
	 */
	@Schema(name = "DashboardKpis")
	public record Kpis(BigDecimal todaySalesTotal, BigDecimal todayProfit, long stockTotal) {
	}

}
