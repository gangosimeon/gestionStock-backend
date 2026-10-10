package com.geststock.customer;

/**
 * Écritures du compte client. Le sens (débit = la dette augmente, crédit = elle diminue) et
 * le type de pièce source découlent du type ; la base impose la même correspondance
 * ({@code ck_customer_account_entry_direction}).
 */
public enum CustomerAccountEntryType {

	/** Reste dû d'une vente à crédit (module ventes). */
	CREDIT_SALE(Direction.DEBIT, "SALE"),

	/** Règlement du client (module paiements / caisse). */
	PAYMENT(Direction.CREDIT, "CUSTOMER_PAYMENT"),

	/** Avoir d'un retour imputé sur la dette (module retours). */
	CREDIT_NOTE(Direction.CREDIT, "CUSTOMER_RETURN"),

	/** Annulation d'une vente à crédit (module ventes). */
	SALE_CANCELLATION(Direction.CREDIT, "SALE");

	public enum Direction {

		DEBIT, CREDIT

	}

	private final Direction direction;

	private final String sourceType;

	CustomerAccountEntryType(Direction direction, String sourceType) {
		this.direction = direction;
		this.sourceType = sourceType;
	}

	public Direction direction() {
		return this.direction;
	}

	/** Type de la pièce à l'origine de l'écriture (colonne {@code source_type}). */
	public String sourceType() {
		return this.sourceType;
	}

}
