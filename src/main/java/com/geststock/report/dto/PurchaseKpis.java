package com.geststock.report.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Achats d'une période.
 *
 * @param receiptsCount  réceptions enregistrées (date de réception dans la période)
 * @param receivedAmount valeur reçue = somme des réceptions (quantité reçue × coût unitaire commandé)
 * @param ordersCount    commandes passées dans la période (hors annulées)
 * @param orderedAmount  montant commandé
 */
@Schema(name = "DashboardPurchaseKpis")
public record PurchaseKpis(long receiptsCount, BigDecimal receivedAmount, long ordersCount, BigDecimal orderedAmount) {
}
