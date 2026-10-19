package com.geststock.stock.dto;

import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.stock.StockTransferStatus;

/**
 * Critères de l'historique des transferts.
 *
 * @param q             texte cherché dans le numéro, le produit (référence, nom) et la note
 * @param direction     {@code SENT} (partis du dépôt courant), {@code RECEIVED} (arrivés), absent : les deux
 * @param from          date du transfert à partir de ce jour (inclus)
 * @param to            date du transfert jusqu'à ce jour (inclus)
 * @param allWarehouses tous les dépôts (exige {@code warehouse:all}) ; {@code direction} est alors ignoré
 */
public record StockTransferFilter(
		@Schema(example = "TRF-2026") String q,
		Direction direction,
		StockTransferStatus status,
		UUID productId,
		@Schema(example = "2026-01-01") LocalDate from,
		@Schema(example = "2026-12-31") LocalDate to,
		Boolean allWarehouses) {

	/** Sens du transfert vu du dépôt courant. */
	public enum Direction {

		SENT, RECEIVED

	}

}
