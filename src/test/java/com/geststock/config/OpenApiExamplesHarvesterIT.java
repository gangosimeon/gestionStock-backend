package com.geststock.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import com.geststock.common.security.Permissions;
import com.geststock.stock.StockFixtures;
import com.geststock.support.PostgresTestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Régénère les exemples du document OpenAPI ({@code src/main/resources/openapi/examples}) à partir
 * d'un vrai parcours métier : chaque exemple est une requête réellement acceptée et la réponse
 * réellement renvoyée. Exécution à la demande :
 *
 * <pre>
 * GESTSTOCK_TEST_TESTCONTAINERS=false ./mvnw verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
 *     -Dit.test=OpenApiExamplesHarvesterIT -Dopenapi.examples.write=true
 * </pre>
 *
 * Données annulées en fin de test (transaction) ; jetons remplacés par des valeurs factices.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@Transactional
class OpenApiExamplesHarvesterIT {

	private static final Path TARGET = Path.of("src", "main", "resources", "openapi", "examples");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private JsonMapper json;

	private UUID manager;

	private UUID depot;

	private final Map<String, ObjectNode> examples = new LinkedHashMap<>();

	@Test
	void harvest() throws Exception {
		assumeTrue(Boolean.getBoolean("openapi.examples.write"), "-Dopenapi.examples.write=true pour régénérer");
		this.manager = this.jdbc.queryForObject("""
				insert into app_user (username, first_name, last_name) values ('marie.k', 'Marie', 'Kouassi') returning id""",
				UUID.class);
		this.depot = this.jdbc.queryForObject(
				"insert into warehouse (code, name, type) values ('MAG-EX', 'Magasin Plateau', 'STORE') returning id",
				UUID.class);
		String frames = this.jdbc.queryForObject("select id::text from category where name = 'Montures'", String.class);

		// Utilisateurs et authentification (vrai jeton pour /me).
		String user = id(call("createUser", post("/api/users"), """
				{"username": "awa.t", "firstName": "Awa", "lastName": "Traoré", "email": "awa.t@optique.ci",
				 "phone": "+225 07 00 00 00 01", "password": "Optique2026!", "roles": ["CAISSIER"],
				 "defaultWarehouseId": "%s"}""".formatted(this.depot)));
		JsonNode session = call("login", post("/api/auth/login"),
				"{\"username\": \"awa.t\", \"password\": \"Optique2026!\"}", false);
		record("getCurrentUser", null, this.mvc
			.perform(get("/api/auth/me").header("Authorization", "Bearer " + session.get("accessToken").asString()))
			.andReturn());
		call("refreshToken", post("/api/auth/refresh"),
				"{\"refreshToken\": \"%s\"}".formatted(session.get("refreshToken").asString()), false);
		call("assignRolesUser", put("/api/users/{id}/roles", user), "{\"roles\": [\"CAISSIER\", \"GESTIONNAIRE\"]}");

		// Catalogue, fournisseurs, achats.
		String frame = id(call("createProduct", post("/api/products"), """
				{"sku": "MON-RB-3025", "barcode": "8053672000001", "name": "Ray-Ban Aviator RB3025", "categoryId": "%s",
				 "purchasePrice": 45000, "retailPrice": 95000, "wholesalePrice": 80000, "alertThreshold": 2,
				 "maxStockLevel": 20}""".formatted(frames)));
		String lens = id(call(null, post("/api/products"), """
				{"sku": "LEN-ACU-30", "name": "Acuvue Oasys (boîte de 6)", "categoryId": "%s", "purchasePrice": 9000,
				 "retailPrice": 18000, "wholesalePrice": 15000, "alertThreshold": 5}""".formatted(frames)));
		String supplier = id(call("createSupplier", post("/api/suppliers"), """
				{"name": "Essilor Côte d'Ivoire", "phone": "+225 27 20 00 00 00", "email": "commandes@essilor.ci",
				 "address": "Zone 4, Abidjan", "deliveryLeadTimeDays": 7}"""));
		String order = id(call("createPurchaseOrder", post("/api/purchases/orders"), """
				{"supplierId": "%s", "supplierReference": "ESS-PO-1182",
				 "lines": [{"productId": "%s", "quantity": 10, "unitPurchasePrice": 44000},
				           {"productId": "%s", "quantity": 20}]}""".formatted(supplier, frame, lens)));
		call("receivePurchaseOrder", post("/api/purchases/orders/{id}/receive", order), """
				{"supplierDeliveryNote": "BL-77810", "lines": [{"productId": "%s", "quantity": 6},
				                                               {"productId": "%s", "quantity": 20}]}"""
			.formatted(frame, lens));
		call("createStockMovement", post("/api/stock/movements"),
				"{\"productId\": \"%s\", \"quantity\": -1, \"reason\": \"LOSS\", \"note\": \"Verre rayé en vitrine\"}"
					.formatted(lens));
		String annex = id(call("createWarehouse", post("/api/warehouses"), """
				{"name": "Dépôt Cocody", "type": "WAREHOUSE", "address": "Cocody Riviera 2", "phone": "+225 27 22 00 00 00"}"""));
		call("transferStock", post("/api/warehouses/transfer"), """
				{"fromWarehouseId": "%s", "toWarehouseId": "%s", "productId": "%s", "quantity": 4,
				 "note": "Réassort dépôt"}""".formatted(this.depot, annex, lens));
		call("listStockLevels", get("/api/stock/levels").param("page", "0").param("size", "2"), null);
		call("listProduct", get("/api/products").param("page", "0").param("size", "2"), null);

		// Clients, caisse, ventes, retours.
		String customer = id(call("createCustomer", post("/api/customers"), """
				{"name": "Clinique Les Lauriers", "type": "COMPANY", "phone": "+225 07 11 22 33 44",
				 "email": "compta@lauriers.ci", "city": "Abidjan", "creditLimit": 500000, "paymentTermsDays": 30}"""));
		call("openCashSession", post("/api/cash-register/open"),
				"{\"openingBalance\": 50000, \"terminal\": \"Caisse 1\", \"note\": \"Fond de caisse du matin\"}");
		String sale = id(call("createSale", post("/api/sales"), """
				{"paymentMethod": "CASH", "items": [{"productId": "%s", "quantity": 2, "discountPercent": 10},
				                                    {"productId": "%s", "quantity": 1}]}""".formatted(frame, lens)));
		String credit = id(call(null, post("/api/sales"), """
				{"customerId": "%s", "paymentMethod": "MOBILE_MONEY", "paidAmount": 20000,
				 "items": [{"productId": "%s", "quantity": 1}]}""".formatted(customer, frame)));
		call("listSale", get("/api/sales").param("page", "0").param("size", "2"), null);
		call("createCustomerReturn", post("/api/sales/{saleId}/returns", sale), """
				{"settlement": "REFUND", "reason": "Monture trop large", "items": [
				  {"productId": "%s", "quantity": 1, "condition": "RESELLABLE"}]}""".formatted(frame));
		String toCancel = id(call(null, post("/api/sales"),
				"{\"paymentMethod\": \"CASH\", \"items\": [{\"productId\": \"%s\", \"quantity\": 1}]}".formatted(lens)));
		call("cancelSale", post("/api/sales/{id}/cancel", toCancel), "{\"reason\": \"Erreur de saisie du produit\"}");
		call("createCustomerPayment", post("/api/customers/{customerId}/payments", customer),
				"{\"amount\": 25000, \"paymentMethod\": \"CASH\", \"note\": \"Acompte\"}");
		call("getCustomerAccount", get("/api/customers/{id}/account", customer), null);
		call("createCashOperation", post("/api/cash-register/operations"),
				"{\"type\": \"OUT\", \"amount\": 5000, \"note\": \"Achat de fournitures\"}");
		call("getCurrentCashSession", get("/api/cash-register/current"), null);
		call("closeCashSession", post("/api/cash-register/close"),
				"{\"countedCash\": 211000, \"note\": \"Pièce de 500 manquante\"}");

		// Inventaire.
		String inventory = id(call("createInventory", post("/api/inventories"),
				"{\"scope\": \"SELECTION\", \"productIds\": [\"%s\"], \"note\": \"Inventaire tournant\"}".formatted(lens)));
		call("startInventory", post("/api/inventories/{id}/start", inventory), null);
		call("recordInventoryCounts", put("/api/inventories/{id}/counts", inventory),
				"{\"counts\": [{\"productId\": \"%s\", \"countedQuantity\": 13, \"note\": \"Une boîte abîmée\"}]}"
					.formatted(lens));
		call("submitInventory", post("/api/inventories/{id}/submit", inventory), null);
		call("validateInventory", post("/api/inventories/{id}/validate", inventory),
				"{\"comment\": \"Écart expliqué : casse\"}");

		// Pilotage.
		call("getDashboard", get("/api/dashboard"), null);
		call("getAlertSummary", get("/api/alerts/summary"), null);
		call("getAlertSettings", get("/api/alerts/settings"), null);
		call("salesReport", get("/api/reports/sales").param("size", "2"), null);
		call("stockReport", get("/api/reports/stock").param("size", "2").param("stockState", "IN_STOCK"), null);
		call("customersReport", get("/api/reports/customers").param("customerId", customer), null);
		call("listAuditLog", get("/api/audit-logs").param("page", "0").param("size", "2").param("userId",
				this.manager.toString()), null);
		assertThat(credit).isNotNull();

		Files.createDirectories(TARGET);
		for (Map.Entry<String, ObjectNode> example : this.examples.entrySet()) {
			Files.writeString(TARGET.resolve(example.getKey() + ".json"),
					this.json.writerWithDefaultPrettyPrinter().writeValueAsString(example.getValue()) + "\n",
					StandardCharsets.UTF_8);
		}
	}

	// --- Outils ------------------------------------------------------------------------------------

	private JsonNode call(String operationId, MockHttpServletRequestBuilder request, String body) throws Exception {
		return call(operationId, request, body, true);
	}

	private JsonNode call(String operationId, MockHttpServletRequestBuilder request, String body, boolean authenticated)
			throws Exception {
		if (authenticated) {
			request.with(authentication(StockFixtures.authentication(this.manager, Permissions.ALL.toArray(String[]::new))))
				.header("X-Warehouse-Id", this.depot.toString());
		}
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		MvcResult result = this.mvc.perform(request).andReturn();
		assertThat(result.getResponse().getStatus()).as(operationId + " : " + result.getResponse().getContentAsString())
			.isLessThan(300);
		if (operationId != null) {
			record(operationId, body, result);
		}
		String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
		return content.isBlank() ? null : this.json.readTree(content);
	}

	private void record(String operationId, String body, MvcResult result) throws Exception {
		ObjectNode example = this.json.createObjectNode();
		if (body != null) {
			example.set("request", this.json.readTree(body));
		}
		String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
		if (!content.isBlank()) {
			example.set("response", sanitize(this.json.readTree(content)));
		}
		this.examples.put(operationId, example);
	}

	/** Listes réduites à 2 éléments, jetons remplacés par des valeurs factices. */
	private JsonNode sanitize(JsonNode node) {
		if (node instanceof ObjectNode object) {
			for (String field : new java.util.ArrayList<>(object.propertyNames())) {
				if (field.equals("accessToken")) {
					object.put(field, "eyJhbGciOiJIUzI1NiJ9.exemple.signature");
				}
				else if (field.equals("refreshToken")) {
					object.put(field, "hT3wq0k9N5Q2x8yL1mZr4vB7cF6dJ0sA");
				}
				else {
					object.set(field, sanitize(object.get(field)));
				}
			}
		}
		else if (node instanceof ArrayNode array) {
			while (array.size() > 2) {
				array.remove(array.size() - 1);
			}
			array.forEach(this::sanitize);
		}
		return node;
	}

	private String id(JsonNode node) {
		return node.get("id").asString();
	}

}
