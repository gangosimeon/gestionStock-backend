package com.geststock.purchase.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonAlias;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.cash.PaymentMethod;

/**
 * Paiement à un fournisseur. Contrat du front : {@code {amount, paymentDateIso, orderId?, note?}}.
 *
 * @param paymentDate       date du règlement ({@code paymentDateIso} accepté) ; aujourd'hui si absente,
 *                          jamais dans le futur
 * @param paymentMethod     espèces par défaut : un paiement en espèces sort de ma caisse ouverte
 * @param orderId           commande réglée ; absente, le paiement solde les commandes les plus
 *                          anciennes
 * @param externalReference référence du virement, du chèque ou de la transaction mobile
 */
@Schema(name = "SupplierPaymentRequest")
public record SupplierPaymentRequest(
		@NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) @Schema(example = "150000") BigDecimal amount,
		@JsonAlias("paymentDateIso") @Schema(example = "2026-10-10") LocalDate paymentDate,
		@Schema(example = "BANK_TRANSFER") PaymentMethod paymentMethod,
		UUID orderId,
		@Size(max = 50) @Schema(example = "VIR-2026-1042") String externalReference,
		@Size(max = 500) String note) {
}
