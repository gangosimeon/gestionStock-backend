package com.geststock.cash.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.cash.CashEntryType;

/**
 * Apport ({@code IN}) ou sortie ({@code OUT}) manuelle d'espèces. Contrat du front :
 * {@code {type, amount, note?}}.
 */
@Schema(name = "CashOperationRequest")
public record CashOperationRequest(
		@NotNull CashEntryType.Direction type,
		@NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) @Schema(example = "5000") BigDecimal amount,
		@Size(max = 500) @Schema(example = "Dépôt en banque") String note) {
}
