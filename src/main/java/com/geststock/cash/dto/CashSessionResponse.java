package com.geststock.cash.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.cash.CashSessionStatus;
import com.geststock.cash.PaymentMethod;

/**
 * Session de caisse. Reprend le contrat du front ({@code id, status, openedAt, openedByUserId,
 * openingBalance, closedAt, closedByUserId, countedCash, cashSalesTotal, totalIn, totalOut,
 * expectedCash, difference}) ; montants calculés par le serveur à partir des écritures.
 *
 * @param difference     écart figé à la clôture ; 0 tant que la caisse est ouverte
 * @param totalsByMethod solde net par moyen de paiement (rapprochement)
 */
@Schema(name = "CashRegisterSession")
public record CashSessionResponse(
		UUID id,
		@Schema(example = "CAI-2026-000012") String number,
		CashSessionStatus status,
		UUID warehouseId,
		String terminal,
		Instant openedAt,
		UUID openedByUserId,
		BigDecimal openingBalance,
		String openingNote,
		Instant closedAt,
		UUID closedByUserId,
		BigDecimal countedCash,
		BigDecimal cashSalesTotal,
		BigDecimal totalIn,
		BigDecimal totalOut,
		BigDecimal expectedCash,
		BigDecimal difference,
		Map<PaymentMethod, BigDecimal> totalsByMethod,
		String closingNote) {
}
