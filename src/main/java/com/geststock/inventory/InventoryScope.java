package com.geststock.inventory;

/** Périmètre d'un inventaire. */
public enum InventoryScope {

	/** Tous les produits actifs du catalogue (inventaire complet du dépôt). */
	FULL,

	/** Produits actifs d'une catégorie (inventaire partiel). */
	CATEGORY,

	/** Produits choisis un à un (inventaire tournant, contrôle ciblé). */
	SELECTION

}
