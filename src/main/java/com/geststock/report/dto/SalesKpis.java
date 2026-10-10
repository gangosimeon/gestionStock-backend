package com.geststock.report.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Ventes d'une période (règles de calcul : {@code docs/dashboard.md}).
 *
 * @param count          ventes validées (hors annulées)
 * @param grossSales     somme des totaux des ventes validées (remises déduites)
 * @param returns        montants remboursés ou crédités par les retours de la période
 * @param netSales       chiffre d'affaires net = grossSales − returns
 * @param costOfSales    coût des ventes = coût des ventes validées − coût des articles revendables retournés
 * @param grossProfit    marge brute = netSales − costOfSales
 * @param marginRate     taux de marge en % de netSales ({@code null} si netSales ≤ 0)
 * @param averageBasket  panier moyen = grossSales / count ({@code null} sans vente)
 * @param cancelledCount ventes annulées pendant la période (information)
 */
@Schema(name = "DashboardSalesKpis")
public record SalesKpis(long count, BigDecimal grossSales, BigDecimal returns, BigDecimal netSales,
		BigDecimal costOfSales, BigDecimal grossProfit, BigDecimal marginRate, BigDecimal averageBasket,
		long cancelledCount) {
}
