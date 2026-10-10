package com.geststock.inventory.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/** Produits à ajouter au périmètre d'un inventaire en brouillon. */
@Schema(name = "InventoryProductsRequest")
public record InventoryProductsRequest(@NotEmpty @Size(max = 5000) List<@NotNull UUID> productIds) {
}
