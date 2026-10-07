package com.geststock.brand.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/** Création ou modification d'une marque. */
@Schema(name = "BrandRequest")
public record BrandRequest(@NotBlank @Size(max = 100) @Schema(example = "Ray-Ban") String name) {
}
