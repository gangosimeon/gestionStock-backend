package com.geststock.integration;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * Événement servi dans une page du flux (schéma {@code ChangeEventBase} et ses variantes, contrat
 * {@code integration/v1}).
 *
 * @param seq        position dans le flux
 * @param type       type de l'événement (discriminant du contrat) ; {@code null} si le type stocké est
 *                   inconnu de ce code (voir {@code typeName})
 * @param typeName   type tel qu'il est stocké, toujours renseigné : c'est la valeur exposée au consommateur,
 *                   qui ignore un type inconnu et avance son curseur
 * @param objectId   identifiant de l'objet
 * @param version    version de l'objet (P3) : {@code object_version} écrit, ou le {@code seq} pour un
 *                   événement mince
 * @param operation  {@code UPSERT} ou {@code ARCHIVE}
 * @param occurredAt horodatage d'écriture de l'événement
 * @param data       état de l'objet : charge écrite, ou valeur recalculée pour un événement mince ;
 *                   {@code null} si aucune (facultatif pour {@code ARCHIVE})
 */
public record ChangeEvent(long seq, IntegrationEventType type, String typeName, UUID objectId, long version,
		IntegrationOperation operation, Instant occurredAt, JsonNode data) {

	public ChangeEvent {
		Objects.requireNonNull(typeName, "typeName");
		if (type != null && !type.name().equals(typeName)) {
			throw new IllegalArgumentException("typeName " + typeName + " différent du type " + type);
		}
	}

	/** Événement d'un type connu. */
	public ChangeEvent(long seq, IntegrationEventType type, UUID objectId, long version, IntegrationOperation operation,
			Instant occurredAt, JsonNode data) {
		this(seq, Objects.requireNonNull(type, "type"), type.name(), objectId, version, operation, occurredAt, data);
	}

	/** {@code false} si le type stocké est inconnu de ce code ({@link #type()} vaut alors {@code null}). */
	public boolean isKnownType() {
		return this.type != null;
	}

}
