package com.geststock.report.dto;

import java.math.BigDecimal;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Produit le plus vendu sur la période.
 *
 * @param quantity quantité vendue nette des retours de la période
 * @param total    chiffre d'affaires net du produit (série « CA » du front)
 * @param profit   marge brute du produit
 */
@Schema(name = "DashboardTopProduct")
public record TopProductPoint(UUID productId, String sku, String name, long quantity, BigDecimal total,
		BigDecimal profit) {
}
