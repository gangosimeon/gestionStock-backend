package com.geststock.product.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.product.ProductUnit;

/**
 * Création ({@code POST}) ou modification complète ({@code PUT}) d'un produit.
 *
 * <p><b>Pas de stock ici.</b> Les champs {@code stockQuantity} et {@code categoryName}
 * envoyés par le front actuel sont ignorés : le stock se modifie uniquement par des
 * mouvements de stock (module stock), le nom de catégorie est déduit de {@code categoryId}.
 */
@Schema(name = "ProductRequest")
public record ProductRequest(
		@NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]{0,49}$",
				message = "1 à 50 caractères : lettres, chiffres, point, tiret ou souligné")
		@Schema(example = "MON-RB3025") String sku,
		@Pattern(regexp = "^[0-9]{8,14}$", message = "8 à 14 chiffres (EAN-8, EAN-13, GTIN-14)")
		@Schema(description = "Code-barres EAN/GTIN", example = "8053672611724") String barcode,
		@NotBlank @Size(max = 200) @Schema(example = "Monture Ray-Ban RB3025") String name,
		@Size(max = 1000) String description,
		@NotNull UUID categoryId,
		UUID brandId,
		@Schema(description = "Fournisseur habituel") UUID supplierId,
		@Schema(description = "Unité de vente (UNIT par défaut)") ProductUnit unit,
		@NotNull @PositiveOrZero @Digits(integer = 12, fraction = 2) @Schema(example = "15000") BigDecimal purchasePrice,
		@NotNull @PositiveOrZero @Digits(integer = 12, fraction = 2) @Schema(example = "35000") BigDecimal retailPrice,
		@NotNull @PositiveOrZero @Digits(integer = 12, fraction = 2) @Schema(example = "28000") BigDecimal wholesalePrice,
		@NotNull @PositiveOrZero @Schema(description = "Seuil d'alerte stock bas", example = "3") Integer alertThreshold,
		@PositiveOrZero @Schema(description = "Niveau de surstock (facultatif)") Integer maxStockLevel) {

	@AssertTrue(message = "doit être supérieur ou égal au seuil d'alerte")
	@Schema(hidden = true)
	public boolean isMaxStockLevelConsistent() {
		return this.maxStockLevel == null || this.alertThreshold == null || this.maxStockLevel >= this.alertThreshold;
	}

}
