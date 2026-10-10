package com.geststock.audit.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Statistiques du journal (contrat front {@code AuditLogStats}).
 *
 * @param mostActiveUser     utilisateur le plus actif sur 30 jours ({@code "-"} si aucun)
 * @param mostFrequentAction action la plus fréquente sur 30 jours ({@code null} si aucune)
 */
@Schema(name = "AuditLogStats")
public record AuditLogStatsResponse(long totalToday, long failuresLast7Days, String mostActiveUser,
		String mostFrequentAction) {
}
