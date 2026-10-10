package com.geststock.audit.dto;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import tools.jackson.databind.JsonNode;

/**
 * Entrée du journal d'audit (contrat front {@code AuditLogEntry}, plus {@code module},
 * {@code warehouseId}, {@code userAgent} et {@code traceId}).
 *
 * @param userId   auteur, relevé par le serveur ; nul pour une connexion refusée
 * @param changes  différences {@code [{field, oldValue, newValue}]}, si pertinent
 * @param before   état avant l'opération (champs sensibles masqués)
 * @param after    état après l'opération (champs sensibles masqués)
 * @param meta     contexte, par exemple {@code {errorCode, httpStatus}} pour un échec
 */
@Schema(name = "AuditLogEntry")
public record AuditLogResponse(
		UUID id,
		Instant createdAt,
		UUID userId,
		String username,
		@Schema(example = "ADMIN") String userRole,
		@Schema(example = "UPDATE") String action,
		@Schema(example = "CATALOG") String module,
		@Schema(example = "PRODUCT") String entityType,
		String entityId,
		String entityLabel,
		String ipAddress,
		String userAgent,
		@Schema(example = "SUCCESS") String status,
		JsonNode changes,
		String details,
		JsonNode before,
		JsonNode after,
		JsonNode meta,
		UUID warehouseId,
		String traceId) {
}
