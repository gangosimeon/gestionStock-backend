package com.geststock.stock.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonAlias;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Transfert entre dépôts d'un ou plusieurs produits ({@code items}, comme une vente). Un seul
 * produit peut encore être envoyé par {@code productId} et {@code quantity} (contrat d'origine).
 */
@Schema(name = "TransferStockRequest")
public record TransferStockRequest(
		@NotNull UUID fromWarehouseId,
		@NotNull UUID toWarehouseId,
		@Size(max = 200) @JsonAlias("lines") List<@Valid @NotNull Item> items,
		@Schema(description = "Contrat d'origine (un seul produit) : préférer items") UUID productId,
		@Schema(description = "Contrat d'origine (un seul produit) : préférer items") @Positive @Max(1_000_000) Integer quantity,
		@Size(max = 500) String note) {

	/** Transfert d'un seul produit (contrat d'origine). */
	public TransferStockRequest(UUID fromWarehouseId, UUID toWarehouseId, UUID productId, Integer quantity,
			String note) {
		this(fromWarehouseId, toWarehouseId, null, productId, quantity, note);
	}

	@AssertTrue(message = "indiquer les produits à transférer (items), ou un produit et sa quantité")
	@Schema(hidden = true)
	public boolean isProductsGiven() {
		return (this.items != null && !this.items.isEmpty()) || (this.productId != null && this.quantity != null);
	}

	/** Lignes demandées : {@code items}, sinon le produit unique du contrat d'origine. */
	public List<Item> requestedItems() {
		if (this.items != null && !this.items.isEmpty()) {
			return this.items;
		}
		return this.productId != null && this.quantity != null ? List.of(new Item(this.productId, this.quantity))
				: List.of();
	}

	/** Produit transféré. */
	@Schema(name = "TransferStockItem")
	public record Item(@NotNull UUID productId, @NotNull @Min(1) @Max(1_000_000) Integer quantity) {
	}

}
