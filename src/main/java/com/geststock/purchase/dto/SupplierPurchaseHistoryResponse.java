package com.geststock.purchase.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Historique d'achats d'un fournisseur (contrat front {@code SupplierPurchaseHistory}) : une
 * entrée par réception, dérivée des bons de réception (plus de table dupliquée).
 *
 * @param reference  numéro de la réception
 * @param itemsCount quantité totale reçue
 */
@Schema(name = "SupplierPurchaseHistory")
public record SupplierPurchaseHistoryResponse(
		UUID id,
		UUID supplierId,
		Instant purchaseDateIso,
		String reference,
		UUID purchaseOrderId,
		String purchaseOrderNumber,
		int itemsCount,
		BigDecimal totalAmount) {
}
