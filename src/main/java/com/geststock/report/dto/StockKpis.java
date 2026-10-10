package com.geststock.report.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Stock à l'instant de l'appel.
 *
 * @param units           quantités en stock (somme des niveaux)
 * @param value           valorisation au dernier prix d'achat des produits (quantités positives)
 * @param productsInStock produits ayant une quantité positive
 * @param outOfStock      ruptures (alerte OUT_OF_STOCK) ; {@code null} sans {@code stock:read}
 * @param belowMinimum    seuils minimum atteints (alerte BELOW_MINIMUM) ; {@code null} sans {@code stock:read}
 * @param lowStock        stocks faibles (alerte LOW_STOCK) ; {@code null} sans {@code stock:read}
 */
@Schema(name = "DashboardStockKpis")
public record StockKpis(long units, BigDecimal value, long productsInStock, Integer outOfStock, Integer belowMinimum,
		Integer lowStock) {
}
