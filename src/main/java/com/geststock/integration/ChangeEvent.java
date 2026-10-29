package com.geststock.integration;

import java.time.Instant;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * Événement servi dans une page du flux (schéma {@code ChangeEventBase} et ses variantes, contrat
 * {@code integration/v1}).
 *
 * @param seq        position dans le flux
 * @param type       type de l'événement (discriminant du contrat)
 * @param objectId   identifiant de l'objet
 * @param version    version de l'objet (P3) : {@code object_version} écrit, ou le {@code seq} pour un
 *                   événement mince
 * @param operation  {@code UPSERT} ou {@code ARCHIVE}
 * @param occurredAt horodatage d'écriture de l'événement
 * @param data       état de l'objet : charge écrite, ou valeur recalculée pour un événement mince ;
 *                   {@code null} si aucune (facultatif pour {@code ARCHIVE})
 */
public record ChangeEvent(long seq, IntegrationEventType type, UUID objectId, long version, IntegrationOperation operation,
		Instant occurredAt, JsonNode data) {
}
