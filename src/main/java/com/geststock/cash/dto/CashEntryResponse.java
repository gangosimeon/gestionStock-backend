package com.geststock.cash.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.cash.CashEntryType;
import com.geststock.cash.PaymentMethod;

/**
 * Écriture de caisse. Reprend le contrat front {@code CashOperation} ({@code id, sessionId,
 * type: IN|OUT, amount, createdAt, createdByUserId, note}).
 *
 * @param number    référence de l'opération (OPC-AAAA-nnnnnn)
 * @param type      sens : IN (encaissement) ou OUT (décaissement)
 * @param entryType nature : SALE, CUSTOMER_PAYMENT, CASH_IN, CORRECTION_IN, EXPENSE_CANCELLATION,
 *                  SALE_REFUND, RETURN_REFUND, CASH_OUT, CORRECTION_OUT, SUPPLIER_PAYMENT, EXPENSE
 */
@Schema(name = "CashOperation")
public record CashEntryResponse(
		UUID id,
		@Schema(example = "OPC-2026-000345") String number,
		UUID sessionId,
		CashEntryType.Direction type,
		CashEntryType entryType,
		PaymentMethod paymentMethod,
		BigDecimal amount,
		UUID customerId,
		String sourceType,
		UUID sourceId,
		String sourceReference,
		String note,
		@Schema(description = "Écriture corrigée par cette correction") UUID reversalOfId,
		Instant createdAt,
		UUID createdByUserId) {
}
