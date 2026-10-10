package com.geststock.report.dto;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;

/**
 * Critères communs des rapports. Chaque rapport n'utilise que ceux qui le concernent
 * (voir la documentation de l'endpoint) ; tous sont facultatifs.
 *
 * @param from          début de période ({@code AAAA-MM-JJ} inclus) ; 30 jours avant {@code to} par défaut
 * @param to            fin de période (incluse) ; aujourd'hui par défaut
 * @param warehouseId   dépôt explicite (doit être accessible) ; dépôt courant par défaut
 * @param allWarehouses tous les dépôts (exige {@code warehouse:all})
 * @param userId        auteur : vendeur, caissier, auteur du mouvement ou de la pièce
 * @param status        statut de la pièce, ou activité du produit / client ({@code ACTIVE}, {@code INACTIVE}, {@code ALL})
 * @param type          type de mouvement de stock ({@code SALE}, {@code RECEIPT}…)
 * @param stockState    état du stock : {@code IN_STOCK}, {@code OUT_OF_STOCK}, {@code BELOW_MINIMUM}, {@code DORMANT}
 * @param withBalance   clients : seulement ceux qui ont une dette
 * @param groupBy       regroupement d'une série : {@code DAY}, {@code MONTH}, {@code YEAR}
 */
public record ReportFilter(
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
		UUID warehouseId, Boolean allWarehouses, UUID productId, UUID categoryId, UUID userId, UUID customerId,
		UUID supplierId, String status, String type, String stockState, Boolean withBalance, String groupBy) {
}
