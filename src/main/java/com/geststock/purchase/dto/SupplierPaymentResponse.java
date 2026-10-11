package com.geststock.purchase.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.cash.PaymentMethod;

/**
 * Paiement fournisseur (contrat du front {@code SupplierPayment}).
 *
 * @param number      référence PAF-AAAA-nnnnnn
 * @param orderId     commande réglée quand le paiement porte sur une seule commande
 * @param allocations répartition sur les commandes (somme = {@code amount})
 */
@Schema(name = "SupplierPayment")
public record SupplierPaymentResponse(
		UUID id,
		String number,
		UUID supplierId,
		LocalDate paymentDateIso,
		BigDecimal amount,
		PaymentMethod paymentMethod,
		UUID orderId,
		List<Allocation> allocations,
		String externalReference,
		String note,
		Instant createdAt,
		UUID createdByUserId) {

	/** Part du paiement imputée sur une commande. */
	@Schema(name = "SupplierPaymentAllocation")
	public record Allocation(UUID orderId, String orderNumber, BigDecimal amount) {
	}

}
