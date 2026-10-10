package com.geststock.cash.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.cash.PaymentMethod;

/**
 * Règlement client. Reprend le contrat front {@code CustomerPayment} ({@code id, customerId,
 * paymentDateIso, amount, note}).
 *
 * @param reference référence de l'encaissement en caisse (OPC-AAAA-nnnnnn)
 */
@Schema(name = "CustomerPayment")
public record CustomerPaymentResponse(
		UUID id,
		UUID customerId,
		LocalDate paymentDateIso,
		BigDecimal amount,
		PaymentMethod paymentMethod,
		String reference,
		UUID sessionId,
		String note,
		Instant createdAt,
		UUID createdByUserId) {
}
