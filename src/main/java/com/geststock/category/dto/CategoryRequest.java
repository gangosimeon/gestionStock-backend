package com.geststock.category.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/** Création ou modification d'une catégorie. */
@Schema(name = "CategoryRequest")
public record CategoryRequest(
		@NotBlank @Size(max = 100) @Schema(example = "Montures") String name,
		@Size(max = 255) @Schema(example = "Montures optiques et solaires") String description) {
}
