package com.geststock.alert;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.geststock.alert.dto.AlertResponse;

/**
 * Règles des alertes, sans accès aux données (testables unitairement). Les requêtes de
 * {@link AlertQueries} ne font que présélectionner les candidats.
 */
final class AlertRules {

	private AlertRules() {
	}

	// --- Stock --------------------------------------------------------------------------------------

	/**
	 * Situation d'un produit dans un dépôt.
	 *
	 * @param dormantSince dernière sortie (vente, transfert), sinon premier mouvement ; {@code null} si non calculé
	 */
	record StockPosition(UUID warehouseId, String warehouseName, UUID productId, String sku, String name,
			boolean active, int alertThreshold, Integer maxStockLevel, int quantity, Instant dormantSince) {

		String label() {
			return this.sku + " · " + this.name;
		}

	}

	/** Seuil minimum effectif : celui du produit, sinon le seuil par défaut. */
	static int minimum(StockPosition p, AlertSettings settings) {
		return p.alertThreshold() > 0 ? p.alertThreshold() : settings.defaultMinStock();
	}

	/** Niveau jusqu'auquel le stock est « faible » : seuil + marge (arrondie au-dessus). */
	static int lowStockLevel(int minimum, AlertSettings settings) {
		if (minimum <= 0 || settings.lowStockMarginPercent() <= 0) {
			return minimum;
		}
		int margin = (int) Math.ceil(minimum * settings.lowStockMarginPercent() / 100.0);
		return minimum + Math.max(1, margin);
	}

	static List<AlertResponse> stock(StockPosition p, AlertSettings settings, Instant now) {
		List<AlertResponse> alerts = new ArrayList<>(2);
		if (!p.active()) {
			if (p.quantity() > 0) {
				alerts.add(stockAlert(AlertType.INACTIVE_PRODUCT_IN_STOCK, p, null, null,
						p.label() + " est désactivé mais il en reste " + p.quantity() + " au " + p.warehouseName()
								+ " (stock immobilisé, invendable)."));
			}
			return alerts;
		}
		int minimum = minimum(p, settings);
		int lowLevel = lowStockLevel(minimum, settings);
		if (p.quantity() <= 0) {
			alerts.add(stockAlert(AlertType.OUT_OF_STOCK, p, minimum, null,
					p.label() + " : rupture au " + p.warehouseName() + "."));
		}
		else if (p.quantity() <= minimum) {
			alerts.add(stockAlert(AlertType.BELOW_MINIMUM, p, minimum, null, p.label() + " : " + p.quantity()
					+ " en stock au " + p.warehouseName() + ", seuil minimum " + minimum + "."));
		}
		else if (p.quantity() <= lowLevel) {
			alerts.add(stockAlert(AlertType.LOW_STOCK, p, lowLevel, null, p.label() + " : " + p.quantity()
					+ " en stock au " + p.warehouseName() + ", proche du seuil minimum (" + minimum + ")."));
		}
		if (p.maxStockLevel() != null && p.quantity() > p.maxStockLevel()) {
			alerts.add(stockAlert(AlertType.OVERSTOCK, p, p.maxStockLevel(), null, p.label() + " : " + p.quantity()
					+ " en stock au " + p.warehouseName() + ", au-delà du stock maximum (" + p.maxStockLevel() + ")."));
		}
		int dormantDays = settings.dormantStockDays();
		if (dormantDays > 0 && p.quantity() > 0 && p.dormantSince() != null
				&& p.dormantSince().isBefore(now.minus(Duration.ofDays(dormantDays)))) {
			long days = ChronoUnit.DAYS.between(p.dormantSince(), now);
			alerts.add(stockAlert(AlertType.DORMANT_STOCK, p, dormantDays, p.dormantSince(), p.label() + " : "
					+ p.quantity() + " en stock au " + p.warehouseName() + " sans aucune sortie depuis " + days + " jours."));
		}
		return alerts;
	}

	private static AlertResponse stockAlert(AlertType type, StockPosition p, Integer threshold, Instant since,
			String message) {
		return alert(type, type.severity(), p.warehouseId(), p.warehouseName(), "PRODUCT", p.productId(), p.label(),
				BigDecimal.valueOf(p.quantity()), threshold != null ? BigDecimal.valueOf(threshold) : null, since,
				message);
	}

	// --- Achats -------------------------------------------------------------------------------------

	record OpenPurchaseOrder(UUID id, String number, String supplierName, UUID warehouseId, String warehouseName,
			LocalDate expectedDeliveryDate, BigDecimal totalAmount, BigDecimal receivedAmount) {
	}

	static AlertResponse purchaseOverdue(OpenPurchaseOrder po, LocalDate today, Instant since) {
		long late = ChronoUnit.DAYS.between(po.expectedDeliveryDate(), today);
		BigDecimal remaining = po.totalAmount().subtract(po.receivedAmount());
		return alert(AlertType.PURCHASE_ORDER_OVERDUE, AlertType.PURCHASE_ORDER_OVERDUE.severity(), po.warehouseId(),
				po.warehouseName(), "PURCHASE_ORDER", po.id(), po.number() + " · " + po.supplierName(),
				BigDecimal.valueOf(late), null, since,
				"Commande " + po.number() + " (" + po.supplierName() + ") attendue le " + po.expectedDeliveryDate()
						+ " : " + late + " jour(s) de retard, " + amount(remaining) + " FCFA restant à recevoir.");
	}

	// --- Clients ------------------------------------------------------------------------------------

	/**
	 * Compte d'un client débiteur.
	 *
	 * @param overdueAmount part de la dette dont l'échéance est passée (règlements imputés aux plus anciennes ventes)
	 * @param oldestDueAt   échéance de la plus ancienne créance impayée échue
	 */
	record CustomerDebt(UUID id, String code, String name, BigDecimal creditLimit, int paymentTermsDays,
			BigDecimal balance, BigDecimal overdueAmount, Instant oldestDueAt) {

		String label() {
			return this.code + " · " + this.name;
		}

	}

	static List<AlertResponse> customer(CustomerDebt c, AlertSettings settings, Instant now) {
		List<AlertResponse> alerts = new ArrayList<>(2);
		if (c.overdueAmount().signum() > 0) {
			long days = c.oldestDueAt() != null ? ChronoUnit.DAYS.between(c.oldestDueAt(), now) : 0;
			alerts.add(alert(AlertType.CUSTOMER_PAYMENT_OVERDUE, AlertType.CUSTOMER_PAYMENT_OVERDUE.severity(), null, null,
					"CUSTOMER", c.id(), c.label(), c.overdueAmount(), BigDecimal.valueOf(c.paymentTermsDays()),
					c.oldestDueAt(),
					c.name() + " : " + amount(c.overdueAmount()) + " FCFA échus (délai " + c.paymentTermsDays()
							+ " jours), plus ancienne échéance dépassée de " + days + " jour(s) ; dette totale "
							+ amount(c.balance()) + " FCFA."));
		}
		int percent = settings.creditLimitWarningPercent();
		if (percent > 0 && c.creditLimit().signum() > 0) {
			BigDecimal warning = c.creditLimit().multiply(BigDecimal.valueOf(percent))
				.divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
			if (c.balance().compareTo(warning) >= 0) {
				boolean reached = c.balance().compareTo(c.creditLimit()) >= 0;
				alerts.add(alert(AlertType.CUSTOMER_CREDIT_LIMIT, reached ? AlertSeverity.WARNING : AlertSeverity.INFO,
						null, null, "CUSTOMER", c.id(), c.label(), c.balance(), c.creditLimit(), null,
						c.name() + " : dette de " + amount(c.balance()) + " FCFA pour un plafond de "
								+ amount(c.creditLimit()) + " FCFA"
								+ (reached ? " (plafond atteint : plus de vente à crédit)." : ".")));
			}
		}
		return alerts;
	}

	// --- Caisse -------------------------------------------------------------------------------------

	record CashSessionRow(UUID id, String number, UUID warehouseId, String warehouseName, String cashierName,
			Instant openedAt, Instant closedAt, BigDecimal difference) {
	}

	static AlertResponse cashOpenTooLong(CashSessionRow s, int maxHours, Instant now) {
		long hours = Duration.between(s.openedAt(), now).toHours();
		return alert(AlertType.CASH_SESSION_OPEN_TOO_LONG, AlertType.CASH_SESSION_OPEN_TOO_LONG.severity(),
				s.warehouseId(), s.warehouseName(), "CASH_SESSION", s.id(), s.number() + " · " + s.cashierName(),
				BigDecimal.valueOf(hours), BigDecimal.valueOf(maxHours), s.openedAt(),
				"Caisse " + s.number() + " de " + s.cashierName() + " ouverte depuis " + hours + " h (clôture oubliée ?).");
	}

	static AlertResponse cashDifference(CashSessionRow s, BigDecimal tolerance) {
		return alert(AlertType.CASH_DIFFERENCE, AlertType.CASH_DIFFERENCE.severity(), s.warehouseId(),
				s.warehouseName(), "CASH_SESSION", s.id(), s.number() + " · " + s.cashierName(), s.difference(),
				tolerance, s.closedAt(),
				"Caisse " + s.number() + " de " + s.cashierName() + " clôturée avec un écart de "
						+ (s.difference().signum() > 0 ? "+" : "") + amount(s.difference()) + " FCFA (tolérance "
						+ amount(tolerance) + ").");
	}

	// --- Inventaires --------------------------------------------------------------------------------

	record PendingInventory(UUID id, String number, UUID warehouseId, String warehouseName, Instant submittedAt,
			int lineCount) {
	}

	static AlertResponse inventoryAwaitingValidation(PendingInventory i) {
		return alert(AlertType.INVENTORY_AWAITING_VALIDATION, AlertType.INVENTORY_AWAITING_VALIDATION.severity(),
				i.warehouseId(), i.warehouseName(), "INVENTORY_SESSION", i.id(), i.number(),
				BigDecimal.valueOf(i.lineCount()), null, i.submittedAt(),
				"Inventaire " + i.number() + " (" + i.lineCount() + " produit(s)) compté, en attente de validation.");
	}

	// --- Outils -------------------------------------------------------------------------------------

	private static AlertResponse alert(AlertType type, AlertSeverity severity, UUID warehouseId, String warehouseName,
			String entityType, UUID entityId, String entityLabel, BigDecimal value, BigDecimal threshold, Instant since,
			String message) {
		String id = type.name() + ":" + (warehouseId != null ? warehouseId : "-") + ":" + entityId;
		return new AlertResponse(id, type, type.category(), severity, type.label(), message, warehouseId, warehouseName,
				entityType, entityId, entityLabel, value, threshold, since);
	}

	private static String amount(BigDecimal value) {
		return String.format(Locale.FRANCE, "%,.0f", value).replace(' ', ' ').replace(' ', ' ');
	}

}
