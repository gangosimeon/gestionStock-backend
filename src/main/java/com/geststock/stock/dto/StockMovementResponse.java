package com.geststock.stock.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.stock.StockMovementType;
import com.geststock.stock.StockSourceType;

/**
 * Mouvement de stock. Champs du contrat du front ({@code id, productId, quantity, reason,
 * createdAt, createdByUserId, note}), complétés.
 *
 * @param quantity variation signée (entrée &gt; 0, sortie &lt; 0)
 * @param reason   catégorie du front (SUPPLY, SALE, LOSS, ADJUSTMENT), déduite de {@code type}
 * @param note     motif saisi ou généré
 */
@Schema(name = "StockMovement")
public record StockMovementResponse(
		UUID id,
		UUID warehouseId,
		UUID productId,
		String productSku,
		String productName,
		int quantity,
		StockMovementType type,
		@Schema(description = "Catégorie du contrat front, déduite du type") StockMovementType.LegacyReason reason,
		String note,
		BigDecimal unitCost,
		@Schema(description = "Stock du produit dans le dépôt après ce mouvement") int balanceAfter,
		StockSourceType sourceType,
		UUID sourceId,
		String sourceReference,
		UUID correlationId,
		UUID reversalOfId,
		Instant createdAt,
		UUID createdByUserId,
		String createdByUsername) {
}
