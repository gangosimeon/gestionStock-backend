package com.geststock.integration.link;

/** Motif de suspension d'une liaison (v14-D10). */
public enum SuspensionReason {

	/** Suspension décidée par une personne disposant de {@code integration:manage}. */
	MANUAL,
	/** La personne qui a autorisé la liaison n'a plus accès aux dépôts de la liaison. */
	AUTHORIZER_LOST_ACCESS

}
