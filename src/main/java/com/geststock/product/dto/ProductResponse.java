package com.geststock.product.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.product.ProductUnit;

/**
 * Produit (contrat du front {@code Product}, complété).
 *
 * @param stockQuantity quantité en stock dans le dépôt courant ({@code X-Warehouse-Id}),
 *                      <b>calculée</b> par le module stock à la lecture ; jamais stockée
 *                      dans le produit ni modifiable par l'API produit
 */
@Schema(name = "Product")
public record ProductResponse(
		UUID id,
		@Schema(example = "MON-RB3025") String sku,
		String barcode,
		@Schema(example = "Monture Ray-Ban RB3025") String name,
		String description,
		UUID categoryId,
		@Schema(example = "Montures") String categoryName,
		UUID brandId,
		@Schema(example = "Ray-Ban") String brandName,
		UUID supplierId,
		ProductUnit unit,
		BigDecimal purchasePrice,
		BigDecimal retailPrice,
		BigDecimal wholesalePrice,
		int alertThreshold,
		Integer maxStockLevel,
		@Schema(description = "Stock du dépôt courant, calculé (lecture seule)", accessMode = Schema.AccessMode.READ_ONLY)
		int stockQuantity,
		boolean active,
		Instant createdAt,
		Instant updatedAt) {
}
