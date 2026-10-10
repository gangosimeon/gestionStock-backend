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
 * Inventaire « en une fois » du front actuel : {@code {note?, lines: [{productId, physicalQuantity}]}}.
 * Parcourt tout le workflow (brouillon → comptage → validation) dans une transaction.
 */
@Schema(name = "LegacyInventoryRequest")
public record LegacyInventoryRequest(
		@Size(max = 1000) String note,
		@NotEmpty(message = "au moins une ligne est requise") @Size(max = 5000) List<@Valid @NotNull Line> lines) {

	@Schema(name = "LegacyInventoryLine")
	public record Line(@NotNull UUID productId, @NotNull @Min(0) @Max(1_000_000) Integer physicalQuantity) {
	}

}
