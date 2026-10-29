package com.geststock.integration.link;

/** Motif de révocation d'une liaison (contrat : {@code Link.revocationReason}). */
public enum RevocationReason {

	/** Révocation demandée par la boutique ({@code POST /link/revoke}, C2). */
	SHOP_REQUEST,
	/** Révocation par une personne GestStock disposant de {@code integration:manage}. */
	GESTSTOCK_ADMIN,
	/** Code d'autorisation rejoué (v14-D14). */
	CODE_REPLAYED,
	/** Liaison en attente dont le code a expiré, révoquée au dépôt d'une nouvelle demande pour la même boutique. */
	EXPIRED

}
