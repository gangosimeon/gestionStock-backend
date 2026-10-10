package com.geststock.inventory.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Saisie de comptages (une partie ou la totalité du périmètre ; un recomptage remplace le
 * précédent). La quantité théorique n'est pas saisie : le serveur la relève au même instant.
 */
@Schema(name = "InventoryCountsRequest")
public record InventoryCountsRequest(@NotEmpty @Size(max = 5000) List<@Valid @NotNull Count> counts) {

	/**
	 * @param note justification d'un écart, par exemple
	 */
	@Schema(name = "InventoryCount")
	public record Count(
			@NotNull UUID productId,
			@NotNull @Min(0) @Max(1_000_000) Integer countedQuantity,
			@Size(max = 500) @Schema(example = "Deux étuis abîmés retirés de la vente") String note) {
	}

}
