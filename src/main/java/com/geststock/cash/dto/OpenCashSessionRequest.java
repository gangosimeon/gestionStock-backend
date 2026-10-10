package com.geststock.cash.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/** Ouverture de caisse. Contrat du front : {@code {openingBalance}}. */
@Schema(name = "OpenCashSessionRequest")
public record OpenCashSessionRequest(
		@NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2) @Schema(example = "20000",
				description = "Fond de caisse en espèces") BigDecimal openingBalance,
		@Size(max = 50) @Schema(example = "Caisse 1", description = "Poste ou tiroir (facultatif)") String terminal,
		@Size(max = 500) String note) {
}
