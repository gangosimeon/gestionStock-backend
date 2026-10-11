package com.geststock.purchase.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Facture fournisseur d'une commande (contrat du front {@code SupplierInvoice}).
 *
 * @param totalAmount montant facturé : valeur reçue, calculée par le serveur
 */
@Schema(name = "SupplierInvoice")
public record SupplierInvoiceResponse(
		UUID purchaseOrderId,
		UUID supplierId,
		@Schema(example = "FA-ESS-2026-0412") String invoiceNumber,
		LocalDate invoiceDateIso,
		BigDecimal totalAmount,
		Instant invoicedAt,
		UUID invoicedByUserId) {
}
