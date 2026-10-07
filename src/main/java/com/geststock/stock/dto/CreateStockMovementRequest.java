package com.geststock.stock.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.stock.StockMovementType;

/**
 * Mouvement manuel depuis l'écran Stock (contrat du front : {@code productId},
 * {@code quantity} signée, {@code reason}, {@code note}). Le dépôt est le dépôt courant
 * ({@code X-Warehouse-Id}), l'auteur l'utilisateur connecté : ni l'un ni l'autre ne se
 * saisit.
 *
 * <p>Correspondance : SUPPLY → {@code RECEIPT} (quantité &gt; 0), SALE → {@code SALE}
 * (&lt; 0), LOSS → {@code LOSS} (&lt; 0), ADJUSTMENT → {@code ADJUSTMENT} (≠ 0).
 */
@Schema(name = "CreateStockMovementRequest")
public record CreateStockMovementRequest(
		@NotNull UUID productId,
		@NotNull @Min(-1_000_000) @Max(1_000_000) @Schema(description = "Variation signée : entrée > 0, sortie < 0",
				example = "5") Integer quantity,
		@NotNull @Schema(example = "SUPPLY") StockMovementType.LegacyReason reason,
		@Size(max = 500) @Schema(example = "Réassort fournisseur") String note,
		@PositiveOrZero @Digits(integer = 12, fraction = 2)
		@Schema(description = "Coût unitaire ; par défaut le prix d'achat du produit") BigDecimal unitCost) {
}
