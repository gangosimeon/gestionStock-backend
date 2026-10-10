package com.geststock.alert.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.alert.AlertCategory;
import com.geststock.alert.AlertSeverity;
import com.geststock.alert.AlertType;

/**
 * Alerte calculée. Elle n'est pas stockée : elle disparaît d'elle-même quand sa cause est
 * réglée (réapprovisionnement, réception, règlement, clôture…).
 *
 * @param id          clé stable de l'alerte ({@code TYPE:dépôt:entité}), utilisable comme clé de liste
 * @param title       libellé du type, en français
 * @param message     phrase prête à afficher
 * @param warehouseId dépôt concerné ({@code null} pour les alertes clients)
 * @param entityType  PRODUCT, PURCHASE_ORDER, CUSTOMER, CASH_SESSION ou INVENTORY_SESSION
 * @param value       valeur constatée (stock, montant dû, écart, heures…)
 * @param threshold   seuil franchi
 * @param since       début de la situation (échéance, ouverture, dernière sortie…), si connu
 */
@Schema(name = "Alert")
public record AlertResponse(
		@Schema(example = "OUT_OF_STOCK:0190d6c0-…:0190d6c1-…") String id,
		AlertType type,
		AlertCategory category,
		AlertSeverity severity,
		String title,
		String message,
		UUID warehouseId,
		String warehouseName,
		@Schema(example = "PRODUCT") String entityType,
		UUID entityId,
		@Schema(example = "MON-RB-001 · Monture Ray-Ban") String entityLabel,
		BigDecimal value,
		BigDecimal threshold,
		Instant since) {
}
