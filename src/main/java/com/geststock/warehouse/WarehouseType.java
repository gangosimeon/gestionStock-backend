package com.geststock.warehouse;

/** Nature d'un dépôt (contrainte {@code ck_warehouse_type}). */
public enum WarehouseType {

	/** Point de vente : ventes et caisse possibles. */
	STORE,

	/** Dépôt de stockage, sans vente. */
	WAREHOUSE

}
