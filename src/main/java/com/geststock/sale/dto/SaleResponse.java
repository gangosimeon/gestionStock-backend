package com.geststock.sale.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.cash.PaymentMethod;
import com.geststock.customer.PriceCategory;
import com.geststock.sale.SaleStatus;

/**
 * Vente. Reprend le contrat du front ({@code id, type, customerId, items, paymentMethod,
 * paidAmount, total, profit, createdAt, createdByUserId}), tous montants calculés par le
 * serveur.
 *
 * @param type         tarif appliqué
 * @param creditAmount reste dû porté au compte du client
 */
@Schema(name = "Sale")
public record SaleResponse(
		UUID id,
		@Schema(example = "VTE-2026-000123") String number,
		PriceCategory type,
		SaleStatus status,
		UUID warehouseId,
		UUID customerId,
		String customerName,
		List<SaleItemResponse> items,
		PaymentMethod paymentMethod,
		BigDecimal subtotal,
		BigDecimal discountTotal,
		BigDecimal total,
		BigDecimal paidAmount,
		BigDecimal creditAmount,
		BigDecimal costTotal,
		BigDecimal profit,
		String note,
		Instant createdAt,
		UUID createdByUserId,
		Instant cancelledAt,
		String cancellationReason,
		@Schema(description = "Espèces remises par le client (vente en espèces, si saisi à la caisse)") BigDecimal cashReceived,
		@Schema(description = "Monnaie rendue = montant reçu − montant payé") BigDecimal changeGiven) {

	/**
	 * Article vendu.
	 *
	 * @param unitPrice     prix unitaire du tarif, relu par le serveur
	 * @param purchasePrice coût unitaire au moment de la vente (nom du contrat front)
	 */
	@Schema(name = "SaleItem")
	public record SaleItemResponse(
			UUID id,
			int lineNumber,
			UUID productId,
			String productSku,
			String productName,
			int quantity,
			BigDecimal unitPrice,
			BigDecimal discountPercent,
			BigDecimal discountAmount,
			BigDecimal lineTotal,
			BigDecimal purchasePrice,
			UUID stockMovementId) {
	}

}
