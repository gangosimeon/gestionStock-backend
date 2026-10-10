package com.geststock.cash.dto;

import java.time.Instant;
import java.util.UUID;

import com.geststock.cash.CashSessionStatus;

/**
 * Critères de l'historique des caisses.
 *
 * @param cashierId     caissier (avec {@code cash:read} ; sinon toujours l'utilisateur courant)
 * @param from          sessions ouvertes à partir de cet instant (inclus)
 * @param to            sessions ouvertes avant cet instant (exclu)
 * @param allWarehouses tous les dépôts (exige {@code warehouse:all}) au lieu du dépôt courant
 */
public record CashSessionFilter(CashSessionStatus status, UUID cashierId, Instant from, Instant to,
		Boolean allWarehouses) {
}
