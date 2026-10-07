package com.geststock.warehouse.dto;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.warehouse.WarehouseType;

/** Dépôt (contrat du front : {@code {id, name}}, complété). */
@Schema(name = "Warehouse")
public record WarehouseResponse(
		UUID id,
		@Schema(example = "MAG-01") String code,
		@Schema(example = "Magasin principal") String name,
		WarehouseType type,
		String address,
		String phone,
		@Schema(description = "Dépôt principal (unique, toujours actif)") boolean main,
		boolean active,
		Instant createdAt,
		Instant updatedAt) {
}
