package com.geststock.alert.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.alert.AlertCategory;
import com.geststock.alert.AlertSeverity;
import com.geststock.alert.AlertType;

/**
 * Synthèse pour le tableau de bord : compteurs (tous les types et gravités présents, à 0 au
 * besoin) et alertes les plus graves.
 *
 * @param warehouseId      dépôt couvert ({@code null} : tous les dépôts)
 * @param stockAlertsCount ruptures + seuils minimum atteints (le « stock ≤ seuil » du front,
 *                         {@code DailyReport.stockAlertsCount})
 * @param top              alertes les plus graves (10 au plus)
 */
@Schema(name = "AlertSummary")
public record AlertSummaryResponse(
		Instant generatedAt,
		UUID warehouseId,
		int total,
		int stockAlertsCount,
		Map<AlertSeverity, Integer> bySeverity,
		Map<AlertCategory, Integer> byCategory,
		Map<AlertType, Integer> byType,
		List<AlertResponse> top) {
}
