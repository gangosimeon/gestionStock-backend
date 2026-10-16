package com.geststock.quote.dto;

import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.quote.QuoteDisplayStatus;

/**
 * Critères de recherche des devis.
 *
 * @param q             texte cherché dans le numéro, le client et l'objet
 * @param status        statut affiché ({@code EXPIRED} : émis et validité dépassée ; {@code ISSUED} les exclut)
 * @param from          date du devis à partir de ce jour (inclus)
 * @param to            date du devis jusqu'à ce jour (inclus)
 * @param allWarehouses tous les dépôts (exige {@code warehouse:all}) au lieu du dépôt courant
 */
public record QuoteFilter(
		@Schema(example = "DEV-2026") String q,
		QuoteDisplayStatus status,
		UUID customerId,
		@Schema(example = "2026-01-01") LocalDate from,
		@Schema(example = "2026-12-31") LocalDate to,
		Boolean allWarehouses) {
}
