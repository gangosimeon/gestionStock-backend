package com.geststock.stock.dto;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/** Transfert entre dépôts (contrat du front {@code TransferStockRequest}). */
@Schema(name = "TransferStockRequest")
public record TransferStockRequest(
		@NotNull UUID fromWarehouseId,
		@NotNull UUID toWarehouseId,
		@NotNull UUID productId,
		@NotNull @Positive @Max(1_000_000) Integer quantity,
		@Size(max = 500) String note) {
}
