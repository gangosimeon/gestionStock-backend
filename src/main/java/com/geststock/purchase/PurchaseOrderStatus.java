package com.geststock.purchase;

/**
 * Cycle de vie d'une commande fournisseur.
 *
 * <pre>
 * ORDERED ──réception partielle──► PARTIALLY_RECEIVED ──réception du reste──► RECEIVED
 *    │                                     │
 *    └──annulation──► CANCELLED            └──solde du reliquat──► CLOSED
 * ORDERED ──réception complète──► RECEIVED
 * </pre>
 */
public enum PurchaseOrderStatus {

	/** Commandée, rien reçu. */
	ORDERED,

	/** Reçue en partie ; le reste est attendu. */
	PARTIALLY_RECEIVED,

	/** Entièrement reçue. */
	RECEIVED,

	/** Soldée : reçue en partie, le reliquat ne sera pas livré. */
	CLOSED,

	/** Annulée avant toute réception. */
	CANCELLED;

	/** Une réception est encore possible. */
	public boolean isReceivable() {
		return this == ORDERED || this == PARTIALLY_RECEIVED;
	}

	/**
	 * Statut du contrat historique du front ({@code PENDING | DELIVERED}) : « en attente » tant
	 * qu'une réception reste possible, « livré » quand plus rien n'est attendu.
	 */
	public String legacyStatus() {
		return switch (this) {
			case ORDERED, PARTIALLY_RECEIVED -> "PENDING";
			case RECEIVED, CLOSED -> "DELIVERED";
			case CANCELLED -> "CANCELLED";
		};
	}

}
