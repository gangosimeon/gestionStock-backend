package com.geststock.inventory.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.inventory.InventoryScope;
import com.geststock.inventory.InventoryStatus;

/**
 * Inventaire. Reprend le contrat front {@code InventorySession} ({@code id, createdAt,
 * createdByUserId, note, lines, itemsCount, totalDifference}).
 *
 * @param itemsCount      nombre de produits du périmètre
 * @param countedCount    nombre de produits comptés
 * @param totalDifference somme des écarts des produits comptés
 */
@Schema(name = "InventorySession")
public record InventoryResponse(
		UUID id,
		@Schema(example = "INV-2026-000004") String number,
		InventoryStatus status,
		UUID warehouseId,
		InventoryScope scope,
		UUID categoryId,
		String note,
		Instant createdAt,
		UUID createdByUserId,
		Instant startedAt,
		Instant submittedAt,
		UUID submittedByUserId,
		Instant validatedAt,
		UUID validatedByUserId,
		String validationComment,
		Instant cancelledAt,
		String cancellationReason,
		int itemsCount,
		long countedCount,
		int totalDifference,
		List<Line> lines) {

	/**
	 * Ligne d'inventaire.
	 *
	 * @param systemQuantity   quantité théorique relevée au comptage (nom du contrat front)
	 * @param physicalQuantity quantité comptée (nom du contrat front)
	 * @param difference       compté − théorique
	 * @param stockMovementId  mouvement INVENTORY qui a appliqué l'écart (après validation)
	 */
	@Schema(name = "InventoryLine")
	public record Line(
			UUID id,
			int lineNumber,
			UUID productId,
			String productSku,
			String productName,
			Integer systemQuantity,
			Integer physicalQuantity,
			Integer difference,
			Instant countedAt,
			UUID countedByUserId,
			String note,
			UUID stockMovementId) {
	}

}
