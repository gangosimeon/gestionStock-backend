package com.geststock.purchase.dto;

import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/** Annulation d'une commande ou solde de son reliquat. */
@Schema(name = "ClosePurchaseOrderRequest")
public record ClosePurchaseOrderRequest(
		@Size(max = 500) @Schema(example = "Rupture chez le fournisseur") String reason) {
}
