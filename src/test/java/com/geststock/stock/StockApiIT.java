package com.geststock.stock;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.geststock.common.security.Permissions;
import com.geststock.support.PostgresTestConfiguration;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API stock de bout en bout (contrat du front, permissions, dépôt courant). Chaque test
 * s'exécute dans une transaction annulée à la fin.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@Transactional
class StockApiIT {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private JsonMapper json;

	private UUID manager;

	private UUID main;

	private UUID depot;

	private UUID product;

	@BeforeEach
	void setUp() {
		this.manager = this.jdbc.queryForObject(
				"insert into app_user (username, last_name) values ('stock_api_mgr', 'Gestionnaire') returning id",
				UUID.class);
		this.main = this.jdbc.queryForObject("select id from warehouse where main", UUID.class);
		this.depot = this.jdbc.queryForObject(
				"insert into warehouse (code, name, type) values ('ZZ-STK', 'Dépôt API stock', 'WAREHOUSE') returning id",
				UUID.class);
		this.product = this.jdbc.queryForObject("""
				insert into product (sku, name, category_id, purchase_price)
				values ('STK-API-1', 'Monture API', (select id from category where name = 'Montures'), 12000)
				returning id""", UUID.class);
	}

	@Test
	void frontDrawerPayloadCreatesAMovementInTheCurrentWarehouse() throws Exception {
		// Charge utile exacte du tiroir « Mouvement de stock » du front.
		movement(manager(), "{\"productId\": \"%s\", \"quantity\": 12, \"reason\": \"SUPPLY\", \"note\": \"Réassort\"}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").isString())
			.andExpect(jsonPath("$.productId").value(this.product.toString()))
			.andExpect(jsonPath("$.quantity").value(12))
			.andExpect(jsonPath("$.reason").value("SUPPLY"))
			.andExpect(jsonPath("$.type").value("RECEIPT"))
			.andExpect(jsonPath("$.note").value("Réassort"))
			.andExpect(jsonPath("$.createdAt").isString())
			.andExpect(jsonPath("$.createdByUserId").value(this.manager.toString()))
			.andExpect(jsonPath("$.createdByUsername").value("stock_api_mgr"))
			.andExpect(jsonPath("$.warehouseId").value(this.main.toString()))
			.andExpect(jsonPath("$.balanceAfter").value(12))
			.andExpect(jsonPath("$.unitCost").value(12000))
			.andExpect(jsonPath("$.productSku").value("STK-API-1"));

		// Le catalogue affiche la quantité calculée depuis stock_level.
		this.mvc.perform(get("/api/products/{id}", this.product).with(manager()))
			.andExpect(jsonPath("$.stockQuantity").value(12));
		this.mvc.perform(get("/api/products/{id}", this.product).with(manager())
			.header("X-Warehouse-Id", this.depot.toString()))
			.andExpect(jsonPath("$.stockQuantity").value(0)); // autre dépôt
	}

	@Test
	void outgoingMovementsUseNegativeQuantitiesAndCannotOversell() throws Exception {
		movement(manager(), "{\"productId\": \"%s\", \"quantity\": 3, \"reason\": \"SUPPLY\"}");

		movement(manager(), "{\"productId\": \"%s\", \"quantity\": -4, \"reason\": \"SALE\"}")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("STOCK_INSUFFICIENT"))
			.andExpect(jsonPath("$.message").value("Stock insuffisant."))
			.andExpect(jsonPath("$.details.available").value(3));
		movement(manager(), "{\"productId\": \"%s\", \"quantity\": -1, \"reason\": \"LOSS\"}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.balanceAfter").value(2));
		movement(manager(), "{\"productId\": \"%s\", \"quantity\": -2, \"reason\": \"ADJUSTMENT\"}")
			.andExpect(jsonPath("$.balanceAfter").value(0));
	}

	@Test
	void validatesServerSide() throws Exception {
		movement(manager(), "{\"productId\": \"%s\", \"quantity\": 0, \"reason\": \"ADJUSTMENT\"}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_STOCK_QUANTITY"));
		movement(manager(), "{\"productId\": \"%s\", \"quantity\": 2, \"reason\": \"LOSS\"}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_STOCK_QUANTITY"));
		movement(manager(), "{\"productId\": \"%s\", \"reason\": \"THEFT\"}").andExpect(status().isBadRequest());
		this.mvc.perform(post("/api/stock/movements").with(manager()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"productId\": \"" + UUID.randomUUID() + "\", \"quantity\": 1, \"reason\": \"SUPPLY\"}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
	}

	@Test
	void historyFiltersByProductAndDateAndStaysInTheCurrentWarehouse() throws Exception {
		movement(manager(), "{\"productId\": \"%s\", \"quantity\": 5, \"reason\": \"SUPPLY\"}");
		movement(manager(), "{\"productId\": \"%s\", \"quantity\": -2, \"reason\": \"SALE\"}");
		this.mvc.perform(post("/api/stock/movements").with(manager()).header("X-Warehouse-Id", this.depot.toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"productId\": \"" + this.product + "\", \"quantity\": 9, \"reason\": \"SUPPLY\"}"))
			.andExpect(status().isCreated());

		this.mvc.perform(get("/api/stock/movements").with(manager()).param("productId", this.product.toString()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(2))
			.andExpect(jsonPath("$.items[0].quantity").value(-2))
			.andExpect(jsonPath("$.items[1].quantity").value(5));
		this.mvc.perform(get("/api/stock/movements").with(manager()).param("productId", this.product.toString())
			.param("from", "2000-01-01T00:00:00.000Z").param("to", "2000-12-31T23:59:59.999Z"))
			.andExpect(jsonPath("$.total").value(0));
		this.mvc.perform(get("/api/stock/movements").with(manager()).param("productId", this.product.toString())
			.param("allWarehouses", "true"))
			.andExpect(jsonPath("$.total").value(3));
		// Recherche texte : produit (référence, nom), utilisateur.
		this.mvc.perform(get("/api/stock/movements").with(manager()).param("q", "stk-api"))
			.andExpect(jsonPath("$.total").value(2));
		this.mvc.perform(get("/api/stock/movements").with(manager()).param("q", "monture api"))
			.andExpect(jsonPath("$.total").value(2));
		this.mvc.perform(get("/api/stock/movements").with(manager()).param("q", "gestionnaire"))
			.andExpect(jsonPath("$.total").value(2));
		this.mvc.perform(get("/api/stock/movements").with(manager()).param("q", "introuvable"))
			.andExpect(jsonPath("$.total").value(0));
		this.mvc.perform(get("/api/stock/levels").with(manager()).param("productId", this.product.toString()))
			.andExpect(jsonPath("$.items[0].quantity").value(3))
			.andExpect(jsonPath("$.items[0].warehouseId").value(this.main.toString()));
	}

	@Test
	void transferMatchesTheFrontContract() throws Exception {
		movement(manager(), "{\"productId\": \"%s\", \"quantity\": 6, \"reason\": \"SUPPLY\"}");

		this.mvc.perform(post("/api/warehouses/transfer").with(manager()).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"fromWarehouseId": "%s", "toWarehouseId": "%s", "productId": "%s", "quantity": 4}
					""".formatted(this.main, this.depot, this.product)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.ok").value(true))
			.andExpect(jsonPath("$.out.quantity").value(-4))
			.andExpect(jsonPath("$.in.quantity").value(4))
			.andExpect(jsonPath("$.in.warehouseId").value(this.depot.toString()));

		this.mvc.perform(post("/api/warehouses/transfer").with(manager()).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"fromWarehouseId": "%s", "toWarehouseId": "%s", "productId": "%s", "quantity": 3}
					""".formatted(this.main, this.depot, this.product)))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("STOCK_INSUFFICIENT"));
		this.mvc.perform(post("/api/warehouses/transfer").with(manager()).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"fromWarehouseId": "%s", "toWarehouseId": "%s", "productId": "%s", "quantity": 1}
					""".formatted(this.main, this.main, this.product)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("Magasins identiques."));
	}

	@Test
	void transferIsANumberedReceiptSeenAsSentAndReceived() throws Exception {
		movement(manager(), "{\"productId\": \"%s\", \"quantity\": 6, \"reason\": \"SUPPLY\"}");

		String id = transfer(4, "Réassort dépôt")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.transfer.number").value(org.hamcrest.Matchers.matchesPattern("TRF-\\d{4}-\\d{6}")))
			.andExpect(jsonPath("$.transfer.status").value("COMPLETED"))
			.andExpect(jsonPath("$.transfer.totalQuantity").value(4))
			.andExpect(jsonPath("$.transfer.note").value("Réassort dépôt"))
			.andExpect(jsonPath("$.transfer.toWarehouseName").value("Dépôt API stock"))
			.andExpect(jsonPath("$.transfer.lines[0].productSku").value("STK-API-1"))
			.andExpect(jsonPath("$.transfer.lines[0].quantity").value(4))
			.andExpect(jsonPath("$.transfer.createdByUsername").value("stock_api_mgr"))
			.andReturn().getResponse().getContentAsString();
		String transferId = this.json.readTree(id).get("transferId").asString();
		String number = this.json.readTree(id).get("transfer").get("number").asString();

		// Les deux mouvements portent le numéro du reçu.
		this.mvc.perform(get("/api/stock/movements").with(manager()).param("q", number))
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].sourceId").value(transferId));
		this.mvc.perform(get("/api/stock/transfers").with(manager()).param("direction", "SENT"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].id").value(transferId));
		this.mvc.perform(get("/api/stock/transfers").with(manager()).param("direction", "RECEIVED"))
			.andExpect(jsonPath("$.total").value(0));
		this.mvc.perform(get("/api/stock/transfers").with(manager()).header("X-Warehouse-Id", this.depot.toString())
			.param("direction", "RECEIVED"))
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].number").value(number));
		this.mvc.perform(get("/api/stock/transfers/{id}", transferId).with(manager()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.fromWarehouseId").value(this.main.toString()));
	}

	@Test
	void severalProductsAreTransferredAndReturnedTogether() throws Exception {
		UUID second = this.jdbc.queryForObject("""
				insert into product (sku, name, category_id, purchase_price)
				values ('STK-API-2', 'Étui API', (select id from category where name = 'Montures'), 2000)
				returning id""", UUID.class);
		movement(manager(), "{\"productId\": \"%s\", \"quantity\": 6, \"reason\": \"SUPPLY\"}");
		this.mvc.perform(post("/api/stock/movements").with(manager()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"productId\": \"" + second + "\", \"quantity\": 10, \"reason\": \"SUPPLY\"}"))
			.andExpect(status().isCreated());

		// Un produit en double : refusé avant tout mouvement.
		this.mvc.perform(post("/api/warehouses/transfer").with(manager()).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"fromWarehouseId": "%s", "toWarehouseId": "%s",
					 "items": [{"productId": "%s", "quantity": 1}, {"productId": "%s", "quantity": 2}]}
					""".formatted(this.main, this.depot, this.product, this.product)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("STOCK_TRANSFER_DUPLICATE_PRODUCT"));

		String body = this.mvc.perform(post("/api/warehouses/transfer").with(manager())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"fromWarehouseId": "%s", "toWarehouseId": "%s", "note": "Réassort",
					 "items": [{"productId": "%s", "quantity": 2}, {"productId": "%s", "quantity": 7}]}
					""".formatted(this.main, this.depot, this.product, second)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.transfer.lines.length()").value(2))
			.andExpect(jsonPath("$.transfer.lines[1].productSku").value("STK-API-2"))
			.andExpect(jsonPath("$.transfer.lines[1].lineNumber").value(2))
			.andExpect(jsonPath("$.transfer.totalQuantity").value(9))
			.andExpect(jsonPath("$.movements.length()").value(4))
			.andReturn().getResponse().getContentAsString();
		String transferId = this.json.readTree(body).get("transferId").asString();

		this.mvc.perform(get("/api/stock/transfers").with(manager()).param("q", "étui"))
			.andExpect(jsonPath("$.total").value(1));
		this.mvc.perform(get("/api/stock/transfers").with(manager()).param("productId", second.toString()))
			.andExpect(jsonPath("$.total").value(1));
		this.mvc.perform(post("/api/stock/transfers/{id}/return", transferId).with(manager()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("RETURNED"));
		this.mvc.perform(get("/api/stock/levels").with(manager()).header("X-Warehouse-Id", this.depot.toString()))
			.andExpect(jsonPath("$.items[*].quantity", org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(0))));
		this.mvc.perform(get("/api/stock/levels").with(manager()).param("productId", second.toString()))
			.andExpect(jsonPath("$.items[0].quantity").value(10));
	}

	@Test
	void transferCanBeReturnedOnceToItsOrigin() throws Exception {
		movement(manager(), "{\"productId\": \"%s\", \"quantity\": 6, \"reason\": \"SUPPLY\"}");
		String transferId = transferIdOf(transfer(4, null));

		this.mvc.perform(post("/api/stock/transfers/{id}/return", transferId).with(manager())
			.header("X-Warehouse-Id", this.depot.toString()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\": \"Erreur de dépôt\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("RETURNED"))
			.andExpect(jsonPath("$.returnReason").value("Erreur de dépôt"))
			.andExpect(jsonPath("$.returnedByUsername").value("stock_api_mgr"))
			.andExpect(jsonPath("$.returnedAt").isString());
		this.mvc.perform(get("/api/stock/levels").with(manager()).param("productId", this.product.toString()))
			.andExpect(jsonPath("$.items[0].quantity").value(6));
		this.mvc.perform(get("/api/stock/levels").with(manager()).header("X-Warehouse-Id", this.depot.toString())
			.param("productId", this.product.toString()))
			.andExpect(jsonPath("$.items[0].quantity").value(0));

		this.mvc.perform(post("/api/stock/transfers/{id}/return", transferId).with(manager()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STOCK_TRANSFER_ALREADY_RETURNED"));
		this.mvc.perform(get("/api/stock/transfers").with(manager()).param("status", "RETURNED"))
			.andExpect(jsonPath("$.total").value(1));
	}

	@Test
	void transferReturnIsRefusedWhenTheStockIsGoneOrFromAnotherWarehouse() throws Exception {
		movement(manager(), "{\"productId\": \"%s\", \"quantity\": 6, \"reason\": \"SUPPLY\"}");
		String transferId = transferIdOf(transfer(4, null));
		this.mvc.perform(post("/api/stock/movements").with(manager()).header("X-Warehouse-Id", this.depot.toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"productId\": \"" + this.product + "\", \"quantity\": -1, \"reason\": \"LOSS\"}"))
			.andExpect(status().isCreated());

		this.mvc.perform(post("/api/stock/transfers/{id}/return", transferId).with(manager()))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("STOCK_INSUFFICIENT"));
		UUID other = this.jdbc.queryForObject(
				"insert into warehouse (code, name, type) values ('ZZ-STK2', 'Autre dépôt', 'WAREHOUSE') returning id",
				UUID.class);
		this.mvc.perform(post("/api/stock/transfers/{id}/return", transferId).with(manager())
			.header("X-Warehouse-Id", other.toString()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("WAREHOUSE_MISMATCH"));
		Authentication reader = StockFixtures.authentication(this.manager, Permissions.STOCK_READ);
		this.mvc.perform(post("/api/stock/transfers/{id}/return", transferId).with(authentication(reader)))
			.andExpect(status().isForbidden());
		this.mvc.perform(get("/api/stock/transfers/{id}", transferId).with(manager()))
			.andExpect(jsonPath("$.status").value("COMPLETED"));
	}

	@Test
	void manualMovementCanBeReversedOnce() throws Exception {
		String id = idOf(movement(manager(), "{\"productId\": \"%s\", \"quantity\": 4, \"reason\": \"SUPPLY\"}"));

		this.mvc.perform(post("/api/stock/movements/{id}/reversal", id).with(manager())
			.contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"Erreur de saisie\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.type").value("REVERSAL"))
			.andExpect(jsonPath("$.quantity").value(-4))
			.andExpect(jsonPath("$.reversalOfId").value(id))
			.andExpect(jsonPath("$.note").value("Erreur de saisie"));
		this.mvc.perform(post("/api/stock/movements/{id}/reversal", id).with(manager()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("MOVEMENT_ALREADY_REVERSED"));
	}

	@Test
	void permissionsAreCheckedServerSide() throws Exception {
		movement(manager(), "{\"productId\": \"%s\", \"quantity\": 2, \"reason\": \"SUPPLY\"}");
		Authentication cashier = StockFixtures.authentication(this.manager, Permissions.STOCK_READ,
				Permissions.PRODUCT_READ);

		this.mvc.perform(get("/api/stock/movements").with(authentication(cashier))).andExpect(status().isOk());
		movement(authentication(cashier), "{\"productId\": \"%s\", \"quantity\": -1, \"reason\": \"SALE\"}")
			.andExpect(status().isForbidden());
		this.mvc.perform(post("/api/warehouses/transfer").with(authentication(cashier))
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"fromWarehouseId": "%s", "toWarehouseId": "%s", "productId": "%s", "quantity": 1}
					""".formatted(this.main, this.depot, this.product)))
			.andExpect(status().isForbidden());
		this.mvc.perform(get("/api/stock/movements").with(authentication(cashier)).param("allWarehouses", "true"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("WAREHOUSE_ACCESS_DENIED"));
		this.mvc.perform(get("/api/stock/movements")).andExpect(status().isUnauthorized());
	}

	@Test
	void userWithoutWarehouseAllCannotTouchAnotherWarehouse() throws Exception {
		// Ni warehouse:all ni affectation : périmètre = dépôt principal.
		Authentication clerk = StockFixtures.authentication(this.manager, Permissions.STOCK_READ,
				Permissions.STOCK_ADJUST, Permissions.STOCK_TRANSFER);

		this.mvc.perform(post("/api/stock/movements").with(authentication(clerk))
			.header("X-Warehouse-Id", this.depot.toString()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"productId\": \"" + this.product + "\", \"quantity\": 1, \"reason\": \"SUPPLY\"}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("WAREHOUSE_ACCESS_DENIED"));
		this.mvc.perform(post("/api/warehouses/transfer").with(authentication(clerk))
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"fromWarehouseId": "%s", "toWarehouseId": "%s", "productId": "%s", "quantity": 1}
					""".formatted(this.main, this.depot, this.product)))
			.andExpect(status().isForbidden());
		assertThat(this.jdbc.queryForObject("select count(*) from stock_movement where product_id = ?", Integer.class,
				this.product)).isZero();
	}

	@Test
	void noEndpointWritesALevelDirectly() throws Exception {
		movement(manager(), "{\"productId\": \"%s\", \"quantity\": 2, \"reason\": \"SUPPLY\"}");
		String body = "{\"productId\": \"" + this.product + "\", \"quantity\": 999}";

		for (MockHttpServletRequestBuilder request : new MockHttpServletRequestBuilder[] { post("/api/stock/levels"),
				put("/api/stock/levels"), patch("/api/stock/levels"), delete("/api/stock/levels"),
				put("/api/stock/movements/" + UUID.randomUUID()), delete("/api/stock/movements/" + UUID.randomUUID()) }) {
			this.mvc.perform(request.with(manager()).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isMethodNotAllowed());
		}
		// Le PUT produit ignore stockQuantity : le stock ne bouge pas.
		this.mvc.perform(put("/api/products/{id}", this.product).with(manager()).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"sku": "STK-API-1", "name": "Monture API", "categoryId": "%s", "purchasePrice": 12000,
					 "retailPrice": 20000, "wholesalePrice": 18000, "stockQuantity": 999, "alertThreshold": 1}
					""".formatted(this.jdbc.queryForObject("select id from category where name = 'Montures'", UUID.class))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.stockQuantity").value(2));
		this.mvc.perform(get("/api/stock/movements").with(manager()).param("productId", this.product.toString()))
			.andExpect(jsonPath("$.items[*].type", not(hasItem("ADJUSTMENT"))));
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor manager() {
		return authentication(StockFixtures.authentication(this.manager, Permissions.ALL.toArray(String[]::new)));
	}

	private ResultActions movement(org.springframework.test.web.servlet.request.RequestPostProcessor actor,
			String template) throws Exception {
		return this.mvc.perform(post("/api/stock/movements").with(actor).contentType(MediaType.APPLICATION_JSON)
			.content(template.formatted(this.product)));
	}

	private ResultActions transfer(int quantity, String note) throws Exception {
		return this.mvc.perform(post("/api/warehouses/transfer").with(manager()).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"fromWarehouseId": "%s", "toWarehouseId": "%s", "productId": "%s", "quantity": %d, "note": %s}
					""".formatted(this.main, this.depot, this.product, quantity,
					note != null ? "\"" + note + "\"" : "null")));
	}

	private String transferIdOf(ResultActions result) throws Exception {
		return this.json.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
			.get("transferId")
			.asString();
	}

	private String idOf(ResultActions result) throws Exception {
		return this.json.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asString();
	}

}
