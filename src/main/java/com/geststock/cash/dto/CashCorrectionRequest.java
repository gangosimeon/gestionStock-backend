package com.geststock.cash.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/** Correction d'un apport ou d'une sortie manuelle : motif obligatoire (tracé). */
@Schema(name = "CashCorrectionRequest")
public record CashCorrectionRequest(
		@NotBlank @Size(max = 400) @Schema(example = "Montant saisi 50 000 au lieu de 5 000") String reason) {
}
