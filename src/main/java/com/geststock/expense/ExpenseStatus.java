package com.geststock.expense;

/** Statut d'une dépense (contrainte {@code ck_expense_status}). */
public enum ExpenseStatus {

	/** Payée : comptée dans les totaux. */
	PAID,

	/** Annulée (motif obligatoire) : visible, hors des totaux. */
	CANCELLED

}
