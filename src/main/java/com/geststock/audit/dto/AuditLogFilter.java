package com.geststock.audit.dto;

import java.util.List;
import java.util.UUID;

/**
 * Critères du journal (contrat front {@code AuditLogFilter}). Les listes acceptent la forme
 * {@code actions=A,B} comme la répétition du paramètre.
 *
 * @param dateFrom à partir de cette date ({@code AAAA-MM-JJ}, journée incluse) ou de cet instant ISO-8601
 * @param dateTo   jusqu'à cette date ({@code AAAA-MM-JJ}, journée incluse) ou cet instant ISO-8601
 * @param search   texte cherché dans l'utilisateur, l'objet, son identifiant et les détails
 */
public record AuditLogFilter(String dateFrom, String dateTo, List<String> actions, List<String> entityTypes,
		List<String> modules, UUID userId, String status, String entityId, String search) {
}
