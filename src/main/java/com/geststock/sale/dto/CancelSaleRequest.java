package com.geststock.sale.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/** Annulation d'une vente : motif obligatoire (tracé dans la vente et le journal d'audit). */
@Schema(name = "CancelSaleRequest")
public record CancelSaleRequest(@NotBlank @Size(max = 500) @Schema(example = "Erreur de saisie") String reason) {
}
