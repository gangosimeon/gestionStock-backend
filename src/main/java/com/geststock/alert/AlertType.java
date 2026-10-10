package com.geststock.alert;

/**
 * Alertes calculées à partir des données existantes. Libellés en français pour l'affichage.
 *
 * <p>Correspondance avec le front : son libellé « Rupture » est {@link #OUT_OF_STOCK}, son
 * « Stock faible » (stock ≤ {@code alertThreshold}) est {@link #BELOW_MINIMUM}.
 */
public enum AlertType {

	/** Stock nul dans un dépôt. */
	OUT_OF_STOCK(AlertCategory.STOCK, AlertSeverity.CRITICAL, "Rupture de stock"),

	/** Stock positif mais au seuil minimum ou en dessous. */
	BELOW_MINIMUM(AlertCategory.STOCK, AlertSeverity.WARNING, "Seuil minimum atteint"),

	/** Stock au-dessus du seuil minimum mais à moins de la marge configurée. */
	LOW_STOCK(AlertCategory.STOCK, AlertSeverity.INFO, "Stock faible"),

	/** Stock au-dessus du stock maximum du produit. */
	OVERSTOCK(AlertCategory.STOCK, AlertSeverity.INFO, "Surstock"),

	/** Produit désactivé (plus vendable) dont il reste du stock. */
	INACTIVE_PRODUCT_IN_STOCK(AlertCategory.STOCK, AlertSeverity.WARNING, "Produit inactif encore en stock"),

	/** Stock sans aucune sortie (vente, transfert) depuis le délai configuré. */
	DORMANT_STOCK(AlertCategory.STOCK, AlertSeverity.INFO, "Stock dormant"),

	/** Commande fournisseur non soldée après la date de livraison prévue. */
	PURCHASE_ORDER_OVERDUE(AlertCategory.PURCHASE, AlertSeverity.WARNING, "Commande fournisseur en retard"),

	/** Créance client non réglée après le délai de paiement. */
	CUSTOMER_PAYMENT_OVERDUE(AlertCategory.CUSTOMER, AlertSeverity.WARNING, "Créance client échue"),

	/** Dette client proche du plafond de crédit, ou l'atteignant. */
	CUSTOMER_CREDIT_LIMIT(AlertCategory.CUSTOMER, AlertSeverity.INFO, "Plafond de crédit bientôt atteint"),

	/** Caisse non clôturée depuis trop longtemps (oubli de clôture). */
	CASH_SESSION_OPEN_TOO_LONG(AlertCategory.CASH, AlertSeverity.WARNING, "Caisse ouverte depuis trop longtemps"),

	/** Clôture de caisse récente avec un écart au-delà de la tolérance. */
	CASH_DIFFERENCE(AlertCategory.CASH, AlertSeverity.WARNING, "Écart de caisse"),

	/** Inventaire compté, en attente de validation. */
	INVENTORY_AWAITING_VALIDATION(AlertCategory.INVENTORY, AlertSeverity.INFO, "Inventaire à valider");

	private final AlertCategory category;

	private final AlertSeverity severity;

	private final String label;

	AlertType(AlertCategory category, AlertSeverity severity, String label) {
		this.category = category;
		this.severity = severity;
		this.label = label;
	}

	public AlertCategory category() {
		return this.category;
	}

	/** Gravité par défaut (certaines alertes s'aggravent, voir {@link AlertRules}). */
	public AlertSeverity severity() {
		return this.severity;
	}

	public String label() {
		return this.label;
	}

}
