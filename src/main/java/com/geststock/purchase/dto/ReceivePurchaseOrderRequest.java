package com.geststock.purchase.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonAlias;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Réception d'une commande. Le front envoie {@code {}} : tout le reste à recevoir est alors
 * réceptionné. {@code lines} permet une réception partielle (produit et quantité reçue).
 *
 * <p>Le coût de chaque ligne reçue est celui de la commande : il n'est pas saisi ici.
 */
@Schema(name = "ReceivePurchaseOrderRequest")
public record ReceivePurchaseOrderRequest(
		@PastOrPresent @JsonAlias("deliveredAtIso")
		@Schema(description = "Date de réception (ISO-8601) ; maintenant si absente") Instant receivedAt,
		@Size(max = 50) @Schema(description = "Numéro du bon de livraison fournisseur") String supplierDeliveryNote,
		@Size(max = 1000) String note,
		@Size(max = 200) @Schema(description = "Absent ou vide : réception de tout le reste à recevoir")
		List<@Valid @NotNull Line> lines) {

	/** Quantité reçue d'un produit de la commande. */
	@Schema(name = "ReceivePurchaseOrderLine")
	public record Line(@NotNull UUID productId, @NotNull @Min(1) @Max(100_000) Integer quantity) {
	}

}
