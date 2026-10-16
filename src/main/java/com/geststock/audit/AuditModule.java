package com.geststock.audit;

import java.util.Map;

/** Module fonctionnel d'une entrée du journal (filtre par domaine). */
public enum AuditModule {

	AUTH, USER, SECURITY, CATALOG, WAREHOUSE, STOCK, SUPPLIER, PURCHASE, CUSTOMER, SALE, RETURN, INVENTORY, CASH,
	EXPENSE, APPOINTMENT, ALERT, AUDIT, SYSTEM;

	private static final Map<String, AuditModule> BY_ENTITY_TYPE = Map.ofEntries(
			Map.entry("USER", USER),
			Map.entry("ROLE", SECURITY),
			Map.entry("PERMISSION", SECURITY),
			Map.entry("PRODUCT", CATALOG),
			Map.entry("CATEGORY", CATALOG),
			Map.entry("BRAND", CATALOG),
			Map.entry("WAREHOUSE", WAREHOUSE),
			Map.entry("STOCK_MOVEMENT", STOCK),
			Map.entry("WAREHOUSE_TRANSFER", STOCK),
			Map.entry("SUPPLIER", SUPPLIER),
			Map.entry("PURCHASE_ORDER", PURCHASE),
			Map.entry("GOODS_RECEIPT", PURCHASE),
			Map.entry("SUPPLIER_PAYMENT", PURCHASE),
			Map.entry("CUSTOMER", CUSTOMER),
			Map.entry("SALE", SALE),
			Map.entry("CUSTOMER_RETURN", RETURN),
			Map.entry("INVENTORY_SESSION", INVENTORY),
			Map.entry("CASH_SESSION", CASH),
			Map.entry("CASH_ENTRY", CASH),
			Map.entry("EXPENSE", EXPENSE),
			Map.entry("APPOINTMENT", APPOINTMENT),
			Map.entry("ALERT_SETTING", ALERT),
			Map.entry("INVOICE_SETTING", SYSTEM),
			Map.entry("AUDIT_LOG", AUDIT),
			Map.entry("SYSTEM", SYSTEM));

	/**
	 * Module d'un type d'entité tracé.
	 *
	 * @throws IllegalArgumentException type inconnu : à déclarer ici (erreur de programmation)
	 */
	public static AuditModule of(String entityType) {
		AuditModule module = BY_ENTITY_TYPE.get(entityType);
		if (module == null) {
			throw new IllegalArgumentException("Type d'entité d'audit non déclaré : " + entityType);
		}
		return module;
	}

}
