package com.geststock.stock.dto;

import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/** Annulation d'un mouvement manuel. */
@Schema(name = "ReverseMovementRequest")
public record ReverseMovementRequest(@Size(max = 500) @Schema(example = "Erreur de saisie") String reason) {
}
