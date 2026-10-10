package com.geststock.sale.dto;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.cash.PaymentMethod;
import com.geststock.sale.SaleStatus;

/**
 * Critères de recherche des ventes.
 *
 * @param q             texte cherché dans le numéro et le nom du client
 * @param from          ventes à partir de cet instant (inclus)
 * @param to            ventes avant cet instant (exclu)
 * @param allWarehouses tous les dépôts (exige {@code warehouse:all}) au lieu du dépôt courant
 */
public record SaleFilter(
		@Schema(example = "VTE-2026") String q,
		SaleStatus status,
		UUID customerId,
		PaymentMethod paymentMethod,
		@Schema(example = "2026-10-01T00:00:00Z") Instant from,
		@Schema(example = "2026-11-01T00:00:00Z") Instant to,
		Boolean allWarehouses) {
}
