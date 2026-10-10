package com.geststock.inventory.dto;

import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/** Commentaire d'une étape : validation, renvoi au comptage ou motif d'annulation. */
@Schema(name = "InventoryCommentRequest")
public record InventoryCommentRequest(@Size(max = 500) String comment) {
}
