package com.geststock.report.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Ventes d'un jour (tous les jours de la période sont présents, à 0 au besoin).
 *
 * @param total  chiffre d'affaires net du jour (ventes − retours) : série « Ventes » du front
 * @param profit marge brute du jour
 */
@Schema(name = "DashboardSalesDay")
public record SalesDayPoint(LocalDate date, long count, BigDecimal grossSales, BigDecimal returns, BigDecimal total,
		BigDecimal profit) {
}
