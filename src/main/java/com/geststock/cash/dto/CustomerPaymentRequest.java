package com.geststock.cash.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.cash.PaymentMethod;

/**
 * Règlement d'une dette client. Contrat du front : {@code {amount, paymentDateIso, note?}}.
 * Le règlement est encaissé maintenant, dans la caisse de l'utilisateur : {@code paymentDateIso}
 * éventuellement envoyé est ignoré (la date est celle de l'encaissement).
 *
 * @param paymentMethod espèces par défaut
 */
@Schema(name = "CustomerPaymentRequest")
public record CustomerPaymentRequest(
		@NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) @Schema(example = "25000") BigDecimal amount,
		PaymentMethod paymentMethod,
		@Size(max = 500) String note) {
}
