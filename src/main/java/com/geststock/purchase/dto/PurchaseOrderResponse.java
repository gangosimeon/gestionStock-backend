package com.geststock.purchase.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.purchase.PurchaseOrderStatus;

/**
 * Commande fournisseur. Reprend le contrat du front ({@code id, supplierId, supplierName,
 * status, createdAt, deliveredAt, lines, totalAmount, paidAmount}).
 *
 * @param status         statut du front : {@code PENDING} (réception possible),
 *                       {@code DELIVERED} (plus rien d'attendu) ou {@code CANCELLED}
 * @param documentStatus statut détaillé du document
 * @param deliveredAt    dernière réception, une fois la commande reçue ou soldée
 * @param paidAmount     0 tant que le module des paiements fournisseurs n'existe pas
 */
@Schema(name = "PurchaseOrder")
public record PurchaseOrderResponse(
		UUID id,
		@Schema(example = "CMD-2026-000042") String number,
		UUID supplierId,
		String supplierName,
		UUID warehouseId,
		@Schema(example = "PENDING") String status,
		PurchaseOrderStatus documentStatus,
		LocalDate expectedDeliveryDate,
		String supplierReference,
		String note,
		List<PurchaseOrderLineResponse> lines,
		BigDecimal totalAmount,
		BigDecimal receivedAmount,
		BigDecimal paidAmount,
		Instant createdAt,
		UUID createdByUserId,
		Instant deliveredAt,
		Instant lastReceiptAt,
		Instant closedAt,
		String closeReason,
		Instant updatedAt) {

	/**
	 * Ligne de commande.
	 *
	 * @param quantity          quantité commandée (nom du contrat front)
	 * @param unitPurchasePrice coût unitaire (nom du contrat front)
	 */
	@Schema(name = "PurchaseOrderLine")
	public record PurchaseOrderLineResponse(
			UUID id,
			int lineNumber,
			UUID productId,
			String productSku,
			String productName,
			int quantity,
			int receivedQuantity,
			int remainingQuantity,
			BigDecimal unitPurchasePrice,
			BigDecimal lineTotal) {
	}

}
