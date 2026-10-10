package com.geststock.returns.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.cash.PaymentMethod;
import com.geststock.returns.ItemCondition;
import com.geststock.returns.ReturnSettlement;

/** Retour client (avoir). */
@Schema(name = "CustomerReturn")
public record CustomerReturnResponse(
		UUID id,
		@Schema(example = "RET-2026-000003") String number,
		UUID saleId,
		UUID warehouseId,
		UUID customerId,
		ReturnSettlement settlement,
		PaymentMethod refundMethod,
		BigDecimal totalAmount,
		String reason,
		Instant createdAt,
		UUID createdByUserId,
		List<Line> lines) {

	/**
	 * @param amount          montant remboursé ou crédité, au prix net de la vente
	 * @param stockMovementId remise en stock (articles revendables)
	 */
	@Schema(name = "CustomerReturnLine")
	public record Line(UUID id, int lineNumber, UUID saleLineId, UUID productId, String productSku, String productName,
			int quantity, BigDecimal amount, ItemCondition condition, UUID stockMovementId) {
	}

}
