package com.geststock.customer.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.customer.CustomerAccountEntryType;

/** Écriture du relevé de compte client. */
@Schema(name = "CustomerAccountEntry")
public record CustomerAccountEntryResponse(
		UUID id,
		UUID customerId,
		CustomerAccountEntryType type,
		CustomerAccountEntryType.Direction direction,
		BigDecimal amount,
		@Schema(description = "Solde dû après l'écriture") BigDecimal balanceAfter,
		String sourceType,
		UUID sourceId,
		String sourceReference,
		String note,
		Instant createdAt,
		UUID createdByUserId) {
}
