package com.geststock.integration.link;

/** Statut d'une liaison ({@code platform.integration_link.status}). */
public enum LinkStatus {

	/** Créée à l'approbation, en attente de l'échange du code ; jamais utilisable. */
	PENDING,
	/** Utilisable. */
	ACTIVE,
	/** Suspendue avec un motif (v14-D10) ; réactivable. */
	SUSPENDED,
	/** Révoquée, définitivement. */
	REVOKED

}
