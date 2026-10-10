package com.geststock.alert;

import com.geststock.common.security.Permissions;

/** Domaine d'une alerte ; on ne voit que les alertes des domaines que l'on peut consulter. */
public enum AlertCategory {

	STOCK(Permissions.STOCK_READ),
	PURCHASE(Permissions.PURCHASE_READ),
	CUSTOMER(Permissions.CUSTOMER_READ),
	CASH(Permissions.CASH_READ),
	INVENTORY(Permissions.INVENTORY_READ);

	private final String readPermission;

	AlertCategory(String readPermission) {
		this.readPermission = readPermission;
	}

	public String readPermission() {
		return this.readPermission;
	}

}
