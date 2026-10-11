package com.geststock.expense;

/**
 * Mode de paiement d'une dépense. Seules les espèces passent par la caisse ; le chèque, propre
 * aux dépenses, n'existe pas en caisse.
 */
public enum ExpensePaymentMethod {

	/** Sortie de la caisse ouverte de l'auteur. */
	CASH,

	MOBILE_MONEY,

	BANK_TRANSFER,

	CHEQUE

}
