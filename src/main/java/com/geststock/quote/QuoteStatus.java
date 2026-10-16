package com.geststock.quote;

/** Statut enregistré d'un devis (contrainte {@code ck_quote_status}). */
public enum QuoteStatus {

	/** En préparation : modifiable, pas encore remis au client. */
	DRAFT,

	/** Remis au client : encore modifiable, en attente de sa réponse. */
	ISSUED,

	/** Accepté par le client : prêt à être facturé. */
	ACCEPTED,

	/** Refusé par le client. */
	REFUSED,

	/** Facturé : la vente issue du devis est enregistrée. */
	INVOICED,

	/** Annulé. */
	CANCELLED

}
