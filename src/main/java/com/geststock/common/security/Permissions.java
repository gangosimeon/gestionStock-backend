package com.geststock.common.security;

import java.util.Set;

/**
 * Catalogue des permissions ({@code domaine:action}), miroir exact de la table
 * {@code permission} (vérifié par {@code PermissionCatalogIT}). À utiliser dans
 * {@link RequiresPermission} pour éviter toute faute de frappe : une permission inconnue
 * ne serait accordée à personne et bloquerait silencieusement l'endpoint.
 *
 * <p>Ajouter une permission = migration Flyway + constante ici (+ {@link #ALL}).
 */
public final class Permissions {

	// Catalogue
	public static final String PRODUCT_READ = "product:read";
	public static final String PRODUCT_CREATE = "product:create";
	public static final String PRODUCT_UPDATE = "product:update";
	public static final String PRODUCT_DELETE = "product:delete";
	public static final String CATEGORY_READ = "category:read";
	public static final String CATEGORY_MANAGE = "category:manage";
	public static final String BRAND_READ = "brand:read";
	public static final String BRAND_MANAGE = "brand:manage";

	// Dépôts et stock
	public static final String WAREHOUSE_READ = "warehouse:read";
	public static final String WAREHOUSE_MANAGE = "warehouse:manage";
	public static final String WAREHOUSE_ALL = "warehouse:all";
	public static final String STOCK_READ = "stock:read";
	public static final String STOCK_ADJUST = "stock:adjust";
	public static final String STOCK_TRANSFER = "stock:transfer";
	public static final String INVENTORY_READ = "inventory:read";
	public static final String INVENTORY_CREATE = "inventory:create";
	public static final String INVENTORY_VALIDATE = "inventory:validate";

	// Fournisseurs et achats
	public static final String SUPPLIER_READ = "supplier:read";
	public static final String SUPPLIER_CREATE = "supplier:create";
	public static final String SUPPLIER_UPDATE = "supplier:update";
	public static final String SUPPLIER_DELETE = "supplier:delete";
	public static final String PURCHASE_READ = "purchase:read";
	public static final String PURCHASE_CREATE = "purchase:create";
	public static final String PURCHASE_RECEIVE = "purchase:receive";
	public static final String PURCHASE_PAY = "purchase:pay";

	// Clients, ventes, caisse
	public static final String CUSTOMER_READ = "customer:read";
	public static final String CUSTOMER_CREATE = "customer:create";
	public static final String CUSTOMER_UPDATE = "customer:update";
	public static final String CUSTOMER_DELETE = "customer:delete";
	public static final String CUSTOMER_PAY = "customer:pay";
	public static final String CUSTOMER_CREDIT = "customer:credit";
	public static final String SALE_READ = "sale:read";
	public static final String SALE_CREATE = "sale:create";
	public static final String SALE_CANCEL = "sale:cancel";
	public static final String RETURN_CREATE = "return:create";
	public static final String CASH_READ = "cash:read";
	public static final String CASH_OPERATE = "cash:operate";

	// Dépenses, rendez-vous
	public static final String EXPENSE_READ = "expense:read";
	public static final String EXPENSE_CREATE = "expense:create";
	public static final String EXPENSE_DELETE = "expense:delete";
	public static final String APPOINTMENT_READ = "appointment:read";
	public static final String APPOINTMENT_MANAGE = "appointment:manage";

	// Pilotage et administration
	public static final String REPORT_READ = "report:read";
	public static final String ALERT_CONFIGURE = "alert:configure";
	public static final String USER_MANAGE = "user:manage";
	public static final String ROLE_MANAGE = "role:manage";
	public static final String AUDIT_READ = "audit:read";
	public static final String SETTINGS_MANAGE = "settings:manage";
	public static final String COMPANY_MANAGE = "company:manage";

	// Devis
	public static final String QUOTE_READ = "quote:read";
	public static final String QUOTE_MANAGE = "quote:manage";

	// Intégration boutique en ligne (lot 2)
	public static final String INTEGRATION_MANAGE = "integration:manage";

	public static final Set<String> ALL = Set.of(
			PRODUCT_READ, PRODUCT_CREATE, PRODUCT_UPDATE, PRODUCT_DELETE, CATEGORY_READ, CATEGORY_MANAGE, BRAND_READ,
			BRAND_MANAGE, WAREHOUSE_READ, WAREHOUSE_MANAGE, WAREHOUSE_ALL, STOCK_READ, STOCK_ADJUST, STOCK_TRANSFER,
			INVENTORY_READ, INVENTORY_CREATE, INVENTORY_VALIDATE, SUPPLIER_READ, SUPPLIER_CREATE, SUPPLIER_UPDATE,
			SUPPLIER_DELETE, PURCHASE_READ, PURCHASE_CREATE, PURCHASE_RECEIVE, PURCHASE_PAY, CUSTOMER_READ,
			CUSTOMER_CREATE, CUSTOMER_UPDATE, CUSTOMER_DELETE, CUSTOMER_PAY, CUSTOMER_CREDIT, SALE_READ, SALE_CREATE,
			SALE_CANCEL, RETURN_CREATE, CASH_READ, CASH_OPERATE, EXPENSE_READ, EXPENSE_CREATE, EXPENSE_DELETE,
			APPOINTMENT_READ, APPOINTMENT_MANAGE, REPORT_READ, ALERT_CONFIGURE, USER_MANAGE, ROLE_MANAGE, AUDIT_READ,
			SETTINGS_MANAGE, COMPANY_MANAGE, QUOTE_READ, QUOTE_MANAGE, INTEGRATION_MANAGE);

	private Permissions() {
	}

}
