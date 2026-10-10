package com.geststock.inventory.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.inventory.InventoryScope;

/**
 * Création d'un inventaire (brouillon).
 *
 * @param warehouseId dépôt inventorié ; dépôt courant (X-Warehouse-Id) s'il est absent
 * @param categoryId  obligatoire pour le périmètre CATEGORY
 * @param productIds  obligatoire pour le périmètre SELECTION
 */
@Schema(name = "CreateInventoryRequest")
public record CreateInventoryRequest(
		UUID warehouseId,
		@NotNull @Schema(example = "CATEGORY") InventoryScope scope,
		UUID categoryId,
		@Size(max = 5000) List<@NotNull UUID> productIds,
		@Size(max = 1000) String note) {

	@AssertTrue(message = "categoryId est requis pour un inventaire par catégorie, et seulement pour lui")
	@Schema(hidden = true)
	public boolean isCategoryConsistent() {
		return this.scope == null || (this.scope == InventoryScope.CATEGORY) == (this.categoryId != null);
	}

	@AssertTrue(message = "productIds est requis pour une sélection, et seulement pour elle")
	@Schema(hidden = true)
	public boolean isSelectionConsistent() {
		boolean listed = this.productIds != null && !this.productIds.isEmpty();
		return this.scope == null || (this.scope == InventoryScope.SELECTION) == listed;
	}

}
