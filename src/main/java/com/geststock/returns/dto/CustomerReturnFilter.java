package com.geststock.returns.dto;

import java.time.Instant;
import java.util.UUID;

import com.geststock.returns.ReturnSettlement;

/**
 * Critères de recherche des retours.
 *
 * @param from          retours à partir de cet instant (inclus)
 * @param to            retours avant cet instant (exclu)
 * @param allWarehouses tous les dépôts (exige {@code warehouse:all}) au lieu du dépôt courant
 */
public record CustomerReturnFilter(UUID customerId, ReturnSettlement settlement, Instant from, Instant to,
		Boolean allWarehouses) {
}
