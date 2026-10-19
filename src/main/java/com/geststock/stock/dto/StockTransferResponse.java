package com.geststock.stock.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.stock.StockTransferStatus;

/**
 * Transfert entre dépôts (pièce du reçu). Noms des dépôts et des produits tels qu'au jour du
 * transfert.
 *
 * @param totalQuantity somme des quantités des lignes
 */
@Schema(name = "StockTransfer")
public record StockTransferResponse(
		UUID id,
		@Schema(example = "TRF-2026-000012") String number,
		UUID fromWarehouseId,
		String fromWarehouseName,
		UUID toWarehouseId,
		String toWarehouseName,
		List<Line> lines,
		int totalQuantity,
		String note,
		StockTransferStatus status,
		Instant createdAt,
		UUID createdByUserId,
		String createdByUsername,
		Instant returnedAt,
		UUID returnedByUserId,
		String returnedByUsername,
		String returnReason) {

	/** Produit transféré (quantité toujours positive). */
	@Schema(name = "StockTransferLine")
	public record Line(int lineNumber, UUID productId, String productSku, String productName, int quantity) {
	}

}
