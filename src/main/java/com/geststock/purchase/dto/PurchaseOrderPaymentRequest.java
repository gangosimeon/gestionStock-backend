package com.geststock.purchase.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonAlias;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.cash.PaymentMethod;

/**
 * Paiement d'une commande (contrat du front {@code POST /purchases/orders/{id}/pay}). Même règle
 * que {@link SupplierPaymentRequest}, la commande étant celle de l'URL.
 */
@Schema(name = "PurchaseOrderPaymentRequest")
public record PurchaseOrderPaymentRequest(
		@NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) @Schema(example = "150000") BigDecimal amount,
		@JsonAlias("paymentDateIso") @Schema(example = "2026-10-10") LocalDate paymentDate,
		@Schema(example = "CASH") PaymentMethod paymentMethod,
		@Size(max = 50) String externalReference,
		@Size(max = 500) String note) {

	public SupplierPaymentRequest forOrder(java.util.UUID orderId) {
		return new SupplierPaymentRequest(this.amount, this.paymentDate, this.paymentMethod, orderId,
				this.externalReference, this.note);
	}

}
