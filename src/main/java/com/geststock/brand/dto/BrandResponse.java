package com.geststock.brand.dto;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** Marque. */
@Schema(name = "Brand")
public record BrandResponse(UUID id, @Schema(example = "Ray-Ban") String name, boolean active, Instant createdAt,
		Instant updatedAt) {
}
