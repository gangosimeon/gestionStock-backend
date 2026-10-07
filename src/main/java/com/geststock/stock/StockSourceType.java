package com.geststock.stock;

/**
 * Pièce à l'origine d'un mouvement. Le module stock ne connaît pas ces pièces : il conserve
 * leur type et leur identifiant ({@code source_id}) tels que fournis par le module appelant.
 */
public enum StockSourceType {

	/** Saisie manuelle depuis l'écran Stock. */
	MANUAL,

	/** Reprise de données (stock d'ouverture, jeu de démonstration). */
	DATA_IMPORT,

	PURCHASE_ORDER,

	SALE,

	CUSTOMER_RETURN,

	SUPPLIER_RETURN,

	INVENTORY_SESSION,

	TRANSFER

}
