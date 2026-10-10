package com.geststock.purchase.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Création d'une commande fournisseur. Contrat du front :
 * {@code {supplierId, lines: [{productId, quantity, unitPurchasePrice}]}}.
 *
 * <p>Le serveur calcule les totaux (les champs {@code lineTotal} et {@code totalAmount}
 * éventuellement envoyés sont ignorés) et relit libellés et prix d'achat de référence.
 */
@Schema(name = "CreatePurchaseOrderRequest")
public record CreatePurchaseOrderRequest(
		@NotNull UUID supplierId,
		@Schema(description = "Dépôt de livraison ; dépôt courant (X-Warehouse-Id) s'il est absent") UUID warehouseId,
		@FutureOrPresent @Schema(description = "Livraison prévue ; par défaut aujourd'hui + délai du fournisseur")
		LocalDate expectedDeliveryDate,
		@Size(max = 50) @Schema(description = "Référence de la commande chez le fournisseur") String supplierReference,
		@Size(max = 1000) String note,
		@NotEmpty(message = "au moins une ligne est requise") @Size(max = 200) List<@Valid @NotNull Line> lines) {

	/**
	 * Ligne commandée.
	 *
	 * @param unitPurchasePrice coût unitaire négocié ; prix d'achat de référence du produit s'il
	 *                          est absent
	 */
	@Schema(name = "CreatePurchaseOrderLine")
	public record Line(
			@NotNull UUID productId,
			@NotNull @Min(1) @Max(100_000) Integer quantity,
			@DecimalMin("0") @Digits(integer = 10, fraction = 2) @Schema(example = "15000")
			BigDecimal unitPurchasePrice) {
	}

}
