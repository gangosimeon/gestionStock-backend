package com.geststock.integration;

import java.util.UUID;

/**
 * Filtre d'une liaison sur les événements du flux : un événement écarté n'est pas servi, mais il est
 * <b>examiné</b> et fait avancer {@code lastSeq} (P1). Le filtre par liaison réel arrive au lot 3.
 */
@FunctionalInterface
public interface ChangeFilter {

	/** Aucun filtre : tous les événements sont servis. */
	ChangeFilter ALL = (type, objectId) -> true;

	/** {@code true} si l'événement de ce type, sur cet objet, est servi à la liaison. */
	boolean accepts(IntegrationEventType type, UUID objectId);

}
