package com.geststock.purchase.dto;

import java.math.BigDecimal;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Compte d'un fournisseur, calculé par le serveur sur les commandes des dépôts visibles.
 *
 * @param orderedAmount     total commandé (hors commandes annulées)
 * @param receivedAmount    valeur des marchandises reçues
 * @param payableAmount     montant dû : commandé pour les commandes en cours ou reçues, reçu pour
 *                          les commandes soldées
 * @param paidAmount        montant réglé
 * @param remainingToPay    reste à payer ({@code payableAmount − paidAmount})
 * @param advanceAmount     acomptes : montant payé au-delà de la valeur reçue (marchandises pas encore
 *                          livrées)
 * @param ordersToPay       commandes ayant un reste à payer
 * @param currentWarehouseRemainingToPay reste à payer dans le dépôt courant : un paiement global ne
 *                          règle que les commandes de ce dépôt (indicatif, revérifié au paiement)
 */
@Schema(name = "SupplierAccount")
public record SupplierAccountResponse(
		UUID supplierId,
		BigDecimal orderedAmount,
		BigDecimal receivedAmount,
		BigDecimal payableAmount,
		BigDecimal paidAmount,
		BigDecimal remainingToPay,
		BigDecimal advanceAmount,
		long ordersToPay,
		@Schema(description = "Reste à payer dans le dépôt courant (plafond d'un paiement global)")
		BigDecimal currentWarehouseRemainingToPay) {
}
