package com.geststock.returns;

/** État d'un article retourné. */
public enum ItemCondition {

	/** Revendable : remis en stock (mouvement CUSTOMER_RETURN au coût de la vente). */
	RESELLABLE,

	/** Défectueux : remboursé ou porté en avoir, mais pas remis en stock. */
	DEFECTIVE

}
