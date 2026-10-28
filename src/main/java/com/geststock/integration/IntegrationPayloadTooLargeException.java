package com.geststock.integration;

/**
 * Charge d'un événement d'intégration plus lourde que {@code geststock.integration.publish.max-payload-size} :
 * erreur de programmation (la charge doit rester minimale), l'événement n'est pas écrit et la
 * transaction de l'appelant est annulée.
 */
public class IntegrationPayloadTooLargeException extends IllegalArgumentException {

	public IntegrationPayloadTooLargeException(IntegrationEventType type, long size, long max) {
		super("Charge de l'événement " + type + " trop lourde : " + size + " octets (maximum " + max + ")");
	}

}
