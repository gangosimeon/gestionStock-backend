package com.geststock.alert;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.geststock.alert.AlertRules.CustomerDebt;
import com.geststock.alert.AlertRules.StockPosition;
import com.geststock.alert.dto.AlertResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Règles des alertes, sans base : seuils, marges, gravités. */
class AlertRulesTest {

	private static final Instant NOW = Instant.parse("2026-10-10T10:00:00Z");

	private static final AlertSettings DEFAULTS = new AlertSettings(0, 50, 90, 0, 30, 90, 24, new BigDecimal("1000"),
			7);

	@Test
	void stockLevelsMapToOutOfStockBelowMinimumAndLowStock() {
		assertThat(types(position(true, 3, null, 0, null))).containsExactly(AlertType.OUT_OF_STOCK);
		assertThat(types(position(true, 3, null, 2, null))).containsExactly(AlertType.BELOW_MINIMUM);
		assertThat(types(position(true, 3, null, 3, null))).as("au seuil = seuil atteint")
			.containsExactly(AlertType.BELOW_MINIMUM);
		// Seuil 3, marge 50 % → 2 unités (arrondi au-dessus) : stock faible jusqu'à 5.
		assertThat(types(position(true, 3, null, 5, null))).containsExactly(AlertType.LOW_STOCK);
		assertThat(types(position(true, 3, null, 6, null))).isEmpty();
		assertThat(AlertRules.stock(position(true, 3, null, 2, null), DEFAULTS, NOW).get(0).severity())
			.isEqualTo(AlertSeverity.WARNING);
		assertThat(AlertRules.stock(position(true, 3, null, 0, null), DEFAULTS, NOW).get(0).severity())
			.isEqualTo(AlertSeverity.CRITICAL);
	}

	@Test
	void theLowStockMarginIsConfigurableAndAtLeastOneUnit() {
		assertThat(AlertRules.lowStockLevel(10, DEFAULTS)).isEqualTo(15);
		assertThat(AlertRules.lowStockLevel(1, DEFAULTS)).as("marge minimale d'une unité").isEqualTo(2);
		assertThat(AlertRules.lowStockLevel(0, DEFAULTS)).as("sans seuil, pas de stock faible").isZero();
		AlertSettings noMargin = new AlertSettings(0, 0, 90, 0, 30, 90, 24, BigDecimal.ZERO, 7);
		assertThat(types(position(true, 3, null, 4, null), noMargin)).isEmpty();
	}

	@Test
	void productsWithoutTheirOwnThresholdUseTheDefaultMinimum() {
		AlertSettings defaultMin = new AlertSettings(5, 50, 90, 0, 30, 90, 24, BigDecimal.ZERO, 7);
		assertThat(types(position(true, 0, null, 4, null))).as("sans seuil par défaut : rien").isEmpty();
		assertThat(types(position(true, 0, null, 4, null), defaultMin)).containsExactly(AlertType.BELOW_MINIMUM);
		assertThat(types(position(true, 2, null, 3, null), defaultMin)).as("le seuil du produit (2) prime")
			.containsExactly(AlertType.LOW_STOCK);
	}

	@Test
	void overstockInactiveProductsAndDormantStock() {
		assertThat(types(position(true, 3, 20, 25, null))).containsExactly(AlertType.OVERSTOCK);
		assertThat(types(position(true, 3, 20, 20, null))).isEmpty();

		// Produit désactivé : seule compte la présence de stock (ni rupture ni seuil).
		assertThat(types(position(false, 3, null, 4, null))).containsExactly(AlertType.INACTIVE_PRODUCT_IN_STOCK);
		assertThat(types(position(false, 3, null, 0, null))).isEmpty();

		Instant longAgo = NOW.minus(Duration.ofDays(120));
		List<AlertResponse> dormant = AlertRules.stock(position(true, 3, null, 10, longAgo), DEFAULTS, NOW);
		assertThat(dormant).extracting(AlertResponse::type).containsExactly(AlertType.DORMANT_STOCK);
		assertThat(dormant.get(0).since()).isEqualTo(longAgo);
		assertThat(dormant.get(0).message()).contains("120 jours");
		assertThat(types(position(true, 3, null, 10, NOW.minus(Duration.ofDays(30))))).isEmpty();
		AlertSettings noDormancy = new AlertSettings(0, 50, 0, 0, 30, 90, 24, BigDecimal.ZERO, 7);
		assertThat(types(position(true, 3, null, 10, longAgo), noDormancy)).isEmpty();
	}

	@Test
	void customerAlertsDistinguishOverdueDebtAndCreditLimit() {
		UUID id = UUID.randomUUID();
		Instant due = NOW.minus(Duration.ofDays(12));
		List<AlertResponse> overdue = AlertRules.customer(new CustomerDebt(id, "CLI-1", "Clinique", new BigDecimal("100000"),
				30, new BigDecimal("40000"), new BigDecimal("30000"), due), DEFAULTS, NOW);
		assertThat(overdue).extracting(AlertResponse::type).containsExactly(AlertType.CUSTOMER_PAYMENT_OVERDUE);
		assertThat(overdue.get(0).value()).isEqualByComparingTo("30000");
		assertThat(overdue.get(0).message()).contains("30 000 FCFA échus").contains("12 jour(s)");
		assertThat(overdue.get(0).warehouseId()).isNull();

		List<AlertResponse> near = AlertRules.customer(new CustomerDebt(id, "CLI-1", "Clinique", new BigDecimal("100000"),
				30, new BigDecimal("90000"), BigDecimal.ZERO, null), DEFAULTS, NOW);
		assertThat(near).extracting(AlertResponse::type, AlertResponse::severity)
			.containsExactly(org.assertj.core.groups.Tuple.tuple(AlertType.CUSTOMER_CREDIT_LIMIT, AlertSeverity.INFO));
		List<AlertResponse> reached = AlertRules.customer(new CustomerDebt(id, "CLI-1", "Clinique",
				new BigDecimal("100000"), 30, new BigDecimal("100000"), BigDecimal.ZERO, null), DEFAULTS, NOW);
		assertThat(reached.get(0).severity()).as("plafond atteint").isEqualTo(AlertSeverity.WARNING);
		assertThat(AlertRules.customer(new CustomerDebt(id, "CLI-1", "Clinique", new BigDecimal("100000"), 30,
				new BigDecimal("89999"), BigDecimal.ZERO, null), DEFAULTS, NOW)).isEmpty();
		// Sans plafond (0), pas d'alerte de plafond.
		assertThat(AlertRules.customer(new CustomerDebt(id, "CLI-1", "Clinique", BigDecimal.ZERO, 30,
				new BigDecimal("5000"), BigDecimal.ZERO, null), DEFAULTS, NOW)).isEmpty();
	}

	@Test
	void alertIdsAreStableKeys() {
		StockPosition p = position(true, 3, null, 0, null);
		assertThat(AlertRules.stock(p, DEFAULTS, NOW).get(0).id())
			.isEqualTo("OUT_OF_STOCK:" + p.warehouseId() + ":" + p.productId());
	}

	@Test
	void theDashboardOrderPutsTheMostSevereFirst() {
		List<AlertResponse> alerts = new java.util.ArrayList<>();
		alerts.addAll(AlertRules.stock(position(true, 3, null, 5, null), DEFAULTS, NOW));
		alerts.addAll(AlertRules.stock(position(true, 3, null, 0, null), DEFAULTS, NOW));
		alerts.addAll(AlertRules.stock(position(true, 3, null, 1, null), DEFAULTS, NOW));
		alerts.sort(AlertService.ORDER);
		assertThat(alerts).extracting(AlertResponse::severity)
			.containsExactly(AlertSeverity.CRITICAL, AlertSeverity.WARNING, AlertSeverity.INFO);
	}

	private static List<AlertType> types(StockPosition position) {
		return types(position, DEFAULTS);
	}

	private static List<AlertType> types(StockPosition position, AlertSettings settings) {
		return AlertRules.stock(position, settings, NOW).stream().map(AlertResponse::type).toList();
	}

	private static StockPosition position(boolean active, int threshold, Integer max, int quantity,
			Instant dormantSince) {
		return new StockPosition(UUID.randomUUID(), "Magasin principal", UUID.randomUUID(), "MON-1", "Monture",
				active, threshold, max, quantity, dormantSince);
	}

}
