package com.geststock.purchase.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** Bon de réception, avec le mouvement de stock produit par chaque ligne. */
@Schema(name = "GoodsReceipt")
public record GoodsReceiptResponse(
		UUID id,
		@Schema(example = "REC-2026-000017") String number,
		UUID purchaseOrderId,
		String purchaseOrderNumber,
		UUID warehouseId,
		Instant receivedAt,
		UUID receivedByUserId,
		String supplierDeliveryNote,
		String note,
		int totalQuantity,
		BigDecimal totalAmount,
		Instant createdAt,
		List<GoodsReceiptLineResponse> lines) {

	@Schema(name = "GoodsReceiptLine")
	public record GoodsReceiptLineResponse(
			UUID id,
			int lineNumber,
			UUID productId,
			String productSku,
			String productName,
			int quantity,
			BigDecimal unitCost,
			BigDecimal lineTotal,
			UUID stockMovementId) {
	}

}
