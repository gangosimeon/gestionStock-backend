package com.geststock.cash.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/** Clôture de caisse. Contrat du front : {@code {countedCash}}. */
@Schema(name = "CloseCashSessionRequest")
public record CloseCashSessionRequest(
		@NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2) @Schema(example = "287500",
				description = "Espèces comptées dans le tiroir") BigDecimal countedCash,
		@Size(max = 500) @Schema(description = "Explication d'un éventuel écart") String note) {
}
