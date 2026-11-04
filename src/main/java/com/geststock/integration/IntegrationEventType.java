package com.geststock.integration;

/**
 * Types d'événements du contrat {@code integration/v1} (champ {@code type}, décision C7). Un type
 * <b>mince</b> ne fige aucune valeur à l'écriture : son contenu est recalculé au moment de servir la
 * page du flux (agrégat calculé, décision P3).
 */
public enum IntegrationEventType {

	CATEGORY(false),

	PRODUCT(false),

	PRODUCT_IMAGE(false),

	/** Mince (G22b, P2) : le prix est recalculé pour la liaison qui lit, jamais figé pour une autre liaison. */
	CHANNEL_PRICE(true),

	AVAILABILITY(true),

	ONLINE_ORDER_STATUS(false);

	private final boolean thin;

	IntegrationEventType(boolean thin) {
		this.thin = thin;
	}

	/** Événement mince : aucune version ni charge à l'écriture (voir {@link IntegrationEvents}). */
	public boolean isThin() {
		return this.thin;
	}

}
