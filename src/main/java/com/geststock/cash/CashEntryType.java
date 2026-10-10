package com.geststock.cash;

/**
 * Types d'écriture de caisse. Le sens découle du type ; la base impose la même correspondance
 * ({@code ck_cash_entry_direction}).
 */
public enum CashEntryType {

	/** Encaissement d'une vente (module ventes). */
	SALE(Direction.IN),

	/** Règlement d'une dette client. */
	CUSTOMER_PAYMENT(Direction.IN),

	/** Apport manuel d'espèces. */
	CASH_IN(Direction.IN),

	/** Correction d'une sortie manuelle saisie par erreur (l'argent revient dans le tiroir). */
	CORRECTION_IN(Direction.IN),

	/** Remboursement d'une vente annulée (module ventes). */
	SALE_REFUND(Direction.OUT),

	/** Remboursement d'un retour client (module retours). */
	RETURN_REFUND(Direction.OUT),

	/** Sortie manuelle d'espèces (dépôt en banque, petite dépense…). */
	CASH_OUT(Direction.OUT),

	/** Correction d'un apport manuel saisi par erreur. */
	CORRECTION_OUT(Direction.OUT);

	/** Sens du mouvement d'argent (contrat front {@code CashOperation.type}). */
	public enum Direction {

		IN, OUT

	}

	private final Direction direction;

	CashEntryType(Direction direction) {
		this.direction = direction;
	}

	public Direction direction() {
		return this.direction;
	}

}
