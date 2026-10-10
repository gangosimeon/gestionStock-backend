package com.geststock.alert;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.geststock.common.security.Permissions;
import com.geststock.stock.StockFixtures;
import com.geststock.support.PostgresTestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Alertes de bout en bout sur PostgreSQL, dans un dépôt dédié : stock (rupture, seuil, stock
 * faible, surstock, produit inactif), commande en retard, créances échues et plafond de
 * crédit, caisses, inventaire à valider ; synthèse du tableau de bord, filtres, seuils
 * modifiables, droits. Chaque test s'exécute dans une transaction annulée.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@Transactional
class AlertApiIT {

	private static final String SETTINGS = """
			{"defaultMinStock": %d, "lowStockMarginPercent": %d, "dormantStockDays": 90, "purchaseOverdueGraceDays": 0,
			 "defaultPaymentTermsDays": 30, "creditLimitWarningPercent": 90, "cashSessionMaxOpenHours": 24,
			 "cashDifferenceTolerance": 1000, "cashDifferenceLookbackDays": 7}""";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private JsonMapper json;

	private UUID manager;

	private UUID depot;

	private UUID outOfStock;

	private UUID belowMinimum;

	private UUID lowStock;

	private UUID healthy;

	private UUID overstock;

	private UUID inactive;

	@BeforeEach
	void setUp() throws Exception {
		this.manager = this.jdbc.queryForObject(
				"insert into app_user (username, first_name, last_name) values ('alert_api_user', 'Awa', 'Koné') returning id",
				UUID.class);
		this.depot = this.jdbc.queryForObject(
				"insert into warehouse (code, name, type) values ('ZZ-ALR', 'Dépôt alertes', 'WAREHOUSE') returning id",
				UUID.class);
		// Valeurs par défaut de V021 (un autre test a pu les modifier puis annuler : rien n'est commité).
		this.outOfStock = product("ALR-OUT", 3, null);
		this.belowMinimum = product("ALR-BELOW", 3, null);
		this.lowStock = product("ALR-LOW", 4, null);
		this.healthy = product("ALR-OK", 3, 8);
		this.overstock = product("ALR-OVER", 0, 5);
		this.inactive = product("ALR-INACT", 3, null);
		move(this.outOfStock, 2, "SUPPLY");
		move(this.outOfStock, -2, "LOSS");
		move(this.belowMinimum, 2, "SUPPLY");
		move(this.lowStock, 6, "SUPPLY"); // seuil 4 + marge 50 % = 6
		move(this.healthy, 7, "SUPPLY");
		move(this.overstock, 9, "SUPPLY");
		move(this.inactive, 2, "SUPPLY");
		this.mvc.perform(delete("/api/products/{id}", this.inactive).with(manager())).andExpect(status().isOk());
	}

	// --- Stock --------------------------------------------------------------------------------------

	@Test
	void stockAlertsFollowTheProductThresholds() throws Exception {
		List<JsonNode> alerts = alerts(get("/api/alerts").param("category", "STOCK"));

		assertThat(alerts).extracting(a -> a.get("type").asString())
			.containsExactly("OUT_OF_STOCK", "BELOW_MINIMUM", "INACTIVE_PRODUCT_IN_STOCK", "LOW_STOCK", "OVERSTOCK");
		assertThat(alerts).extracting(a -> a.get("severity").asString())
			.containsExactly("CRITICAL", "WARNING", "WARNING", "INFO", "INFO");
		assertThat(entityIds(alerts)).containsExactly(this.outOfStock.toString(), this.belowMinimum.toString(),
				this.inactive.toString(), this.lowStock.toString(), this.overstock.toString());

		JsonNode out = alerts.get(0);
		assertThat(out.get("id").asString()).isEqualTo("OUT_OF_STOCK:" + this.depot + ":" + this.outOfStock);
		assertThat(out.get("category").asString()).isEqualTo("STOCK");
		assertThat(out.get("title").asString()).isEqualTo("Rupture de stock");
		assertThat(out.get("warehouseId").asString()).isEqualTo(this.depot.toString());
		assertThat(out.get("warehouseName").asString()).isEqualTo("Dépôt alertes");
		assertThat(out.get("entityType").asString()).isEqualTo("PRODUCT");
		assertThat(out.get("entityLabel").asString()).isEqualTo("ALR-OUT · Produit ALR-OUT");
		assertThat(out.get("value").asInt()).isZero();
		assertThat(out.get("threshold").asInt()).isEqualTo(3);
		assertThat(alerts.get(1).get("message").asString()).contains("2 en stock").contains("seuil minimum 3");
		assertThat(alerts.get(4).get("threshold").asInt()).as("stock maximum").isEqualTo(5);

		// Réapprovisionné : l'alerte disparaît d'elle-même.
		move(this.outOfStock, 10, "SUPPLY");
		assertThat(entityIds(alerts(get("/api/alerts").param("category", "STOCK"))))
			.doesNotContain(this.outOfStock.toString());
	}

	@Test
	void thresholdsAreConfigurableWithoutRestartAndTraced() throws Exception {
		// Marge à 0 : plus de « stock faible » ; seuil par défaut 10 : le produit sans seuil (9) l'atteint.
		this.mvc.perform(put("/api/alerts/settings").with(manager()).contentType(MediaType.APPLICATION_JSON)
			.content(SETTINGS.formatted(10, 0)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.defaultMinStock").value(10))
			.andExpect(jsonPath("$.lowStockMarginPercent").value(0));
		this.mvc.perform(get("/api/alerts/settings").with(stockReader()))
			.andExpect(jsonPath("$.defaultMinStock").value(10))
			.andExpect(jsonPath("$.cashDifferenceTolerance").value(1000));

		List<JsonNode> alerts = alerts(get("/api/alerts").param("category", "STOCK"));
		assertThat(alerts).extracting(a -> a.get("type").asString())
			.doesNotContain("LOW_STOCK")
			.contains("OVERSTOCK");
		assertThat(alerts).filteredOn(a -> a.get("entityId").asString().equals(this.overstock.toString()))
			.extracting(a -> a.get("type").asString())
			.containsExactlyInAnyOrder("BELOW_MINIMUM", "OVERSTOCK");
		assertThat(this.jdbc.queryForObject("""
				select count(*) from audit_log
				where action = 'UPDATE' and entity_type = 'ALERT_SETTING' and user_id = ? and status = 'SUCCESS'""",
				Integer.class, this.manager)).isEqualTo(1);

		this.mvc.perform(put("/api/alerts/settings").with(manager()).contentType(MediaType.APPLICATION_JSON)
			.content(SETTINGS.formatted(-1, 50)))
			.andExpect(status().isBadRequest());
		this.mvc.perform(put("/api/alerts/settings").with(manager()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"defaultMinStock\": 0}"))
			.andExpect(status().isBadRequest());
		this.mvc.perform(put("/api/alerts/settings").with(stockReader()).contentType(MediaType.APPLICATION_JSON)
			.content(SETTINGS.formatted(0, 50)))
			.andExpect(status().isForbidden());
	}

	// --- Autres domaines ----------------------------------------------------------------------------

	@Test
	void purchaseCashInventoryAndCustomerAlerts() throws Exception {
		UUID supplier = this.jdbc.queryForObject(
				"insert into supplier (code, name) values ('FRS-ALR', 'Fournisseur alertes') returning id", UUID.class);
		UUID late = purchaseOrder("BC-IT-ALR-1", supplier, "current_date - 3");
		purchaseOrder("BC-IT-ALR-2", supplier, "current_date + 3");

		UUID cashier = this.jdbc.queryForObject(
				"insert into app_user (username, first_name, last_name) values ('alert_cashier', 'Yao', 'Kouadio') returning id",
				UUID.class);
		UUID forgotten = this.jdbc.queryForObject("""
				insert into cash_session (number, warehouse_id, cashier_id, status, opening_balance, opened_at)
				values ('CAI-IT-ALR-1', ?, ?, 'OPEN', 0, now() - interval '30 hours') returning id""", UUID.class,
				this.depot, cashier);
		UUID shortfall = closedSession("CAI-IT-ALR-2", cashier, 45000, 50000);
		closedSession("CAI-IT-ALR-3", cashier, 49500, 50000); // dans la tolérance

		String inventory = id(this.mvc.perform(post("/api/inventories").with(manager()).header("X-Warehouse-Id", this.depot)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"scope\": \"SELECTION\", \"productIds\": [\"%s\"]}".formatted(this.healthy))));
		this.mvc.perform(post("/api/inventories/{id}/start", inventory).with(manager())).andExpect(status().isOk());
		this.mvc.perform(put("/api/inventories/{id}/counts", inventory).with(manager())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"counts\": [{\"productId\": \"%s\", \"countedQuantity\": 7}]}".formatted(this.healthy)))
			.andExpect(status().isOk());
		this.mvc.perform(post("/api/inventories/{id}/submit", inventory).with(manager())).andExpect(status().isOk());

		// Client sans délai propre (30 j par défaut) : 50 000 il y a 40 j, 20 000 réglés → 30 000 échus ;
		// 10 000 d'il y a 2 j pas encore dus.
		UUID debtor = customer("CLI-ALR-1", 200000, 0);
		entry(debtor, "CREDIT_SALE", 50000, 40);
		entry(debtor, "CREDIT_NOTE", 20000, 5);
		entry(debtor, "CREDIT_SALE", 10000, 2);
		// Délai propre de 10 j : 5 000 d'il y a 15 j sont échus.
		UUID shortTerms = customer("CLI-ALR-2", 0, 10);
		entry(shortTerms, "CREDIT_SALE", 5000, 15);
		// Proche du plafond (95 %), rien d'échu.
		UUID nearLimit = customer("CLI-ALR-3", 100000, 60);
		entry(nearLimit, "CREDIT_SALE", 95000, 1);

		List<JsonNode> alerts = alerts(get("/api/alerts"));

		assertThat(only(alerts, "PURCHASE_ORDER_OVERDUE")).singleElement().satisfies(a -> {
			assertThat(a.get("entityId").asString()).isEqualTo(late.toString());
			assertThat(a.get("value").asInt()).as("jours de retard").isEqualTo(3);
			assertThat(a.get("message").asString()).contains("BC-IT-ALR-1").contains("Fournisseur alertes");
		});
		assertThat(only(alerts, "CASH_SESSION_OPEN_TOO_LONG")).singleElement().satisfies(a -> {
			assertThat(a.get("entityId").asString()).isEqualTo(forgotten.toString());
			assertThat(a.get("entityLabel").asString()).isEqualTo("CAI-IT-ALR-1 · Yao Kouadio");
			assertThat(a.get("value").asInt()).isEqualTo(30);
		});
		assertThat(only(alerts, "CASH_DIFFERENCE")).singleElement().satisfies(a -> {
			assertThat(a.get("entityId").asString()).isEqualTo(shortfall.toString());
			assertThat(a.get("value").asInt()).isEqualTo(-5000);
		});
		assertThat(only(alerts, "INVENTORY_AWAITING_VALIDATION")).singleElement()
			.satisfies(a -> assertThat(a.get("entityId").asString()).isEqualTo(inventory));

		List<JsonNode> overdue = only(alerts, "CUSTOMER_PAYMENT_OVERDUE");
		assertThat(byEntity(overdue, debtor).get("value").decimalValue()).isEqualByComparingTo("30000");
		assertThat(byEntity(overdue, debtor).get("threshold").asInt()).as("délai par défaut").isEqualTo(30);
		assertThat(byEntity(overdue, debtor).get("warehouseId").isNull()).isTrue();
		assertThat(byEntity(overdue, shortTerms).get("value").decimalValue()).isEqualByComparingTo("5000");
		assertThat(entityIds(overdue)).doesNotContain(nearLimit.toString());
		assertThat(entityIds(only(alerts, "CUSTOMER_CREDIT_LIMIT"))).contains(nearLimit.toString())
			.doesNotContain(debtor.toString());
	}

	// --- Tableau de bord, filtres, droits -------------------------------------------------------------

	@Test
	void theDashboardSummaryCountsTheVisibleAlerts() throws Exception {
		this.mvc.perform(get("/api/alerts/summary").with(stockReader()).header("X-Warehouse-Id", this.depot))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.warehouseId").value(this.depot.toString()))
			.andExpect(jsonPath("$.total").value(5))
			.andExpect(jsonPath("$.stockAlertsCount").value(2))
			.andExpect(jsonPath("$.bySeverity.CRITICAL").value(1))
			.andExpect(jsonPath("$.bySeverity.WARNING").value(2))
			.andExpect(jsonPath("$.bySeverity.INFO").value(2))
			.andExpect(jsonPath("$.byCategory.STOCK").value(5))
			.andExpect(jsonPath("$.byCategory.CUSTOMER").doesNotExist())
			.andExpect(jsonPath("$.byType.OUT_OF_STOCK").value(1))
			.andExpect(jsonPath("$.byType.DORMANT_STOCK").value(0))
			.andExpect(jsonPath("$.byType.CASH_DIFFERENCE").doesNotExist())
			.andExpect(jsonPath("$.top[0].type").value("OUT_OF_STOCK"))
			.andExpect(jsonPath("$.top.length()").value(5))
			.andExpect(jsonPath("$.generatedAt").isString());
	}

	@Test
	void alertsAreFilteredPaginatedAndRestrictedToWhatTheUserMaySee() throws Exception {
		assertThat(alerts(get("/api/alerts").param("type", "OUT_OF_STOCK", "BELOW_MINIMUM")))
			.extracting(a -> a.get("type").asString())
			.containsExactly("OUT_OF_STOCK", "BELOW_MINIMUM");
		assertThat(alerts(get("/api/alerts").param("severity", "CRITICAL")))
			.allSatisfy(a -> assertThat(a.get("severity").asString()).isEqualTo("CRITICAL"))
			.extracting(a -> a.get("entityId").asString())
			.contains(this.outOfStock.toString());
		this.mvc.perform(get("/api/alerts").with(manager()).header("X-Warehouse-Id", this.depot)
			.param("category", "STOCK").param("page", "1").param("size", "2"))
			.andExpect(jsonPath("$.total").value(5))
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].type").value("INACTIVE_PRODUCT_IN_STOCK"));

		// Un magasinier ne voit que le stock ; un vendeur sans domaine surveillé n'a pas accès.
		this.mvc.perform(get("/api/alerts").with(stockReader()).header("X-Warehouse-Id", this.depot))
			.andExpect(jsonPath("$.total").value(5))
			.andExpect(jsonPath("$.items[*].category").value(org.hamcrest.Matchers.everyItem(
					org.hamcrest.Matchers.is("STOCK"))));
		this.mvc.perform(get("/api/alerts").with(actor(Permissions.SALE_READ, Permissions.WAREHOUSE_READ)))
			.andExpect(status().isForbidden());
		this.mvc.perform(get("/api/alerts").with(actor(Permissions.STOCK_READ, Permissions.WAREHOUSE_READ))
			.param("allWarehouses", "true"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("WAREHOUSE_ACCESS_DENIED"));
		this.mvc.perform(get("/api/alerts/summary").with(manager()).param("allWarehouses", "true"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.warehouseId").doesNotExist());
		this.mvc.perform(get("/api/alerts").with(manager()).param("type", "UNKNOWN")).andExpect(status().isBadRequest());
	}

	// --- Outils ------------------------------------------------------------------------------------

	private List<JsonNode> alerts(MockHttpServletRequestBuilder request) throws Exception {
		String body = this.mvc.perform(request.with(manager()).header("X-Warehouse-Id", this.depot))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		List<JsonNode> items = new ArrayList<>();
		this.json.readTree(body).get("items").forEach(items::add);
		return items;
	}

	private static List<JsonNode> only(List<JsonNode> alerts, String type) {
		return alerts.stream().filter(a -> a.get("type").asString().equals(type)).toList();
	}

	private static List<String> entityIds(List<JsonNode> alerts) {
		return alerts.stream().map(a -> a.get("entityId").asString()).toList();
	}

	private static JsonNode byEntity(List<JsonNode> alerts, UUID entityId) {
		return alerts.stream().filter(a -> a.get("entityId").asString().equals(entityId.toString())).findFirst()
			.orElseThrow(() -> new AssertionError("Aucune alerte pour " + entityId));
	}

	private UUID product(String sku, int threshold, Integer max) {
		return this.jdbc.queryForObject("""
				insert into product (sku, name, category_id, purchase_price, retail_price, wholesale_price, alert_threshold,
				                     max_stock_level)
				values (?, ?, (select id from category where name = 'Montures'), 1000, 2000, 1800, ?, ?) returning id""",
				UUID.class, sku, "Produit " + sku, threshold, max);
	}

	private void move(UUID productId, int quantity, String reason) throws Exception {
		this.mvc.perform(post("/api/stock/movements").with(manager()).header("X-Warehouse-Id", this.depot)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"productId\": \"%s\", \"quantity\": %d, \"reason\": \"%s\"}".formatted(productId, quantity,
					reason)))
			.andExpect(status().isCreated());
	}

	private UUID purchaseOrder(String number, UUID supplier, String expected) {
		return this.jdbc.queryForObject("""
				insert into purchase_order (number, supplier_id, supplier_name, warehouse_id, expected_delivery_date,
				                            total_amount)
				values (?, ?, 'Fournisseur alertes', ?, %s, 150000) returning id""".formatted(expected), UUID.class,
				number, supplier, this.depot);
	}

	private UUID closedSession(String number, UUID cashier, int counted, int expected) {
		return this.jdbc.queryForObject("""
				insert into cash_session (number, warehouse_id, cashier_id, status, opening_balance, opened_at, closed_at,
				                          closed_by, counted_cash, expected_cash, difference)
				values (?, ?, ?, 'CLOSED', 0, now() - interval '2 days', now() - interval '1 day', ?, ?, ?, ?)
				returning id""", UUID.class, number, this.depot, cashier, cashier, counted, expected, counted - expected);
	}

	private UUID customer(String code, int creditLimit, int termsDays) {
		return this.jdbc.queryForObject("""
				insert into customer (code, name, credit_limit, payment_terms_days) values (?, ?, ?, ?) returning id""",
				UUID.class, code, "Client " + code, creditLimit, termsDays);
	}

	private void entry(UUID customerId, String type, int amount, int daysAgo) {
		this.jdbc.update("""
				insert into customer_account_entry (customer_id, entry_type, direction, amount, balance_after, source_type,
				                                    source_id, created_at, created_by)
				values (?, ?, ?, ?, 0, ?, ?, now() - make_interval(days => ?), ?)""", customerId, type,
				type.equals("CREDIT_SALE") ? "DEBIT" : "CREDIT", amount,
				type.equals("CREDIT_SALE") ? "SALE" : "CUSTOMER_RETURN", UUID.randomUUID(), daysAgo, this.manager);
	}

	private String id(org.springframework.test.web.servlet.ResultActions result) throws Exception {
		return this.json.readTree(result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
			.get("id")
			.asString();
	}

	private RequestPostProcessor manager() {
		return actor(Permissions.ALL.toArray(String[]::new));
	}

	/** Magasinier : stock seulement, tous dépôts (pour consulter le dépôt de test). */
	private RequestPostProcessor stockReader() {
		return actor(Permissions.STOCK_READ, Permissions.WAREHOUSE_READ, Permissions.WAREHOUSE_ALL);
	}

	private RequestPostProcessor actor(String... permissions) {
		return authentication(StockFixtures.authentication(this.manager, permissions));
	}

}
