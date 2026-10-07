package com.geststock.category.dto;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** Catégorie (contrat du front : {@code {id, name}}, complété). */
@Schema(name = "Category")
public record CategoryResponse(
		UUID id,
		@Schema(example = "Montures") String name,
		String description,
		boolean active,
		Instant createdAt,
		Instant updatedAt) {
}
