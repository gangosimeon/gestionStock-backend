package com.geststock.purchase.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.purchase.PurchaseOrderStatus;

/**
 * Critères de recherche des commandes fournisseurs.
 *
 * @param q              texte cherché dans le numéro et le nom du fournisseur
 * @param status         statut détaillé
 * @param supplierId     fournisseur
 * @param allWarehouses  tous les dépôts (exige {@code warehouse:all}) au lieu du dépôt courant
 */
public record PurchaseOrderFilter(
		@Schema(example = "CMD-2026") String q,
		PurchaseOrderStatus status,
		UUID supplierId,
		Boolean allWarehouses) {
}
