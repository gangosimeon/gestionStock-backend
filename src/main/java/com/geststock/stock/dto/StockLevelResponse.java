package com.geststock.stock.dto;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** Quantité disponible d'un produit dans un dépôt (lecture seule). */
@Schema(name = "StockLevel")
public record StockLevelResponse(
		UUID warehouseId,
		UUID productId,
		String productSku,
		String productName,
		int quantity,
		UUID lastMovementId,
		Instant updatedAt) {
}
