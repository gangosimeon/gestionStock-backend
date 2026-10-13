package com.geststock.stock.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.stock.StockMovementType;

/**
 * Critères de l'historique des mouvements (contrat du front : {@code from}, {@code to},
 * {@code productId}).
 *
 * @param from          date ISO incluse
 * @param to            date ISO incluse
 * @param productId     produit
 * @param type          types de mouvement (répétable)
 * @param allWarehouses tous les dépôts au lieu du dépôt courant ({@code warehouse:all} requis)
 */
public record StockMovementFilter(
		@Schema(description = "Recherche texte : produit (nom, référence, code-barres), motif, pièce source, utilisateur", example = "ray-ban") String q,
		@Schema(example = "2026-10-01T00:00:00Z") Instant from,
		@Schema(example = "2026-10-31T23:59:59Z") Instant to,
		UUID productId,
		List<StockMovementType> type,
		@Schema(description = "Vue consolidée de tous les dépôts (permission warehouse:all)") Boolean allWarehouses) {
}
