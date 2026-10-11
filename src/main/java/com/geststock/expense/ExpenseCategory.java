package com.geststock.expense;

/** Catégorie d'une dépense (contrainte {@code ck_expense_category}). */
public enum ExpenseCategory {

	TRANSPORT,

	SALARY,

	RENT,

	/** Électricité, eau, internet, téléphone. */
	UTILITIES,

	/** Fournitures de bureau et consommables. */
	SUPPLIES,

	/** Entretien et réparations. */
	MAINTENANCE,

	/** Impôts et taxes. */
	TAXES,

	OTHER

}
