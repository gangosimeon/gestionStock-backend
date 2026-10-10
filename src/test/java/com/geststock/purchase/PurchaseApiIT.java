package com.geststock.purchase;

import java.time.LocalDate;
import java.time.ZoneOffset;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import com.geststock.common.security.Permissions;
import com.geststock.stock.StockFixtures;
import com.geststock.support.PostgresTestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Processus d'achat de bout en bout sur PostgreSQL : commande → réception(s) → stock, contrat
 * du front, calculs serveur, statuts, dépôts et droits. Chaque test s'exécute dans une
 * transaction annulée ; les contraintes différées de V015 sont forcées avant la fin
 * ({@code SET CONSTRAINTS ALL IMMEDIATE}) pour vérifier la cohérence réception ↔ stock.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@Transactional
class PurchaseApiIT {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private JsonMapper json;

	private UUID manager;

	private UUID main;

	private UUID depot;

	private UUID supplier;

	private UUID frame;

	private UUID lens;

	@BeforeEach
	void setUp() {
		this.manager = this.jdbc.queryForObject(
				"insert into app_user (username, last_name) values ('purchase_api_mgr', 'Acheteur') returning id",
				UUID.class);
		this.main = this.jdbc.queryForObject("select id from warehouse where main", UUID.class);
		this.depot = this.jdbc.queryForObject(
				"insert into warehouse (code, name, type) values ('ZZ-ACH', 'Dépôt API achats', 'WAREHOUSE') returning id",
				UUID.class);
		this.supplier = this.jdbc.queryForObject("""
				insert into supplier (code, name, delivery_lead_time_days, payment_terms_days)
				values ('FOU-API', 'Essilor API', 5, 30) returning id""", UUID.class);
		this.frame = product("ACH-MON-1", "Monture achat", 15000);
		this.lens = product("ACH-LEN-1", "Lentilles achat", 4500);
	}

	// --- Commande -------------------------------------------------------------------------------

	@Test
	void theFrontPayloadCreatesAnOrderWhoseTotalsAreComputedByTheServer() throws Exception {
		// Charge utile du drawer « Nouvelle commande », complétée de totaux falsifiés : ignorés.
		ResultActions created = createOrder("""
				{"supplierId": "%s", "totalAmount": 1, "lines": [
				  {"productId": "%s", "quantity": 3, "unitPurchasePrice": 14000, "lineTotal": 1},
				  {"productId": "%s", "quantity": 10}]}
				""".formatted(this.supplier, this.frame, this.lens))
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", startsWith("/api/purchases/orders/")))
			.andExpect(jsonPath("$.number").value(matchesPattern("CMD-\\d{4}-\\d{6}")))
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andExpect(jsonPath("$.documentStatus").value("ORDERED"))
			.andExpect(jsonPath("$.supplierId").value(this.supplier.toString()))
			.andExpect(jsonPath("$.supplierName").value("Essilor API"))
			.andExpect(jsonPath("$.warehouseId").value(this.main.toString()))
			.andExpect(jsonPath("$.createdByUserId").value(this.manager.toString()))
			.andExpect(jsonPath("$.createdAt").isString())
			.andExpect(jsonPath("$.expectedDeliveryDate").value(LocalDate.now(ZoneOffset.UTC).plusDays(5).toString()))
			.andExpect(jsonPath("$.lines[0].productSku").value("ACH-MON-1"))
			.andExpect(jsonPath("$.lines[0].productName").value("Monture achat"))
			.andExpect(jsonPath("$.lines[0].quantity").value(3))
			.andExpect(jsonPath("$.lines[0].unitPurchasePrice").value(14000))
			.andExpect(jsonPath("$.lines[0].lineTotal").value(42000))
			.andExpect(jsonPath("$.lines[1].unitPurchasePrice").value(4500))
			.andExpect(jsonPath("$.lines[1].lineTotal").value(45000))
			.andExpect(jsonPath("$.totalAmount").value(87000))
			.andExpect(jsonPath("$.paidAmount").value(0))
			.andExpect(jsonPath("$.deliveredAt").doesNotExist());
		String id = idOf(created);

		assertThat(level(this.main, this.frame)).as("une commande ne touche pas le stock").isZero();
		this.mvc.perform(get("/api/purchases/orders").with(manager()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].id", hasItem(id)));
		this.mvc.perform(get("/api/purchases/orders/{id}", id).with(manager()))
			.andExpect(jsonPath("$.lines.length()").value(2));
		checkDeferredConstraints();
	}

	@Test
	void creationIsValidatedServerSide() throws Exception {
		createOrder("{\"supplierId\": \"%s\", \"lines\": []}".formatted(this.supplier))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		createOrder(order(this.frame, "0", null)).andExpect(status().isBadRequest());
		createOrder(order(this.frame, "2", "-1")).andExpect(status().isBadRequest());
		createOrder(order(this.frame, "2", "10.005")).andExpect(status().isBadRequest());
		createOrder("{\"lines\": [{\"productId\": \"%s\", \"quantity\": 1}]}".formatted(this.frame))
			.andExpect(status().isBadRequest());

		createOrder(order(UUID.randomUUID(), "1", null))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
		createOrder("""
				{"supplierId": "%s", "lines": [{"productId": "%s", "quantity": 1}]}
				""".formatted(UUID.randomUUID(), this.frame))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("SUPPLIER_NOT_FOUND"));
		createOrder("""
				{"supplierId": "%s", "lines": [{"productId": "%s", "quantity": 1}, {"productId": "%s", "quantity": 2}]}
				""".formatted(this.supplier, this.frame, this.frame))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PURCHASE_DUPLICATE_PRODUCT"));

		// Désactivations par l'API (le contexte de persistance est partagé par les requêtes du test).
		this.mvc.perform(delete("/api/products/{id}", this.lens).with(manager())).andExpect(status().isOk());
		createOrder(order(this.lens, "1", null))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("PRODUCT_INACTIVE"));
		this.mvc.perform(delete("/api/suppliers/{id}", this.supplier).with(manager())).andExpect(status().isOk());
		createOrder(order(this.frame, "1", null))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("SUPPLIER_INACTIVE"));
	}

	// --- Réception ------------------------------------------------------------------------------

	@Test
	void theFrontReceiveButtonPutsTheWholeOrderInStockInOneTransaction() throws Exception {
		String id = idOf(createOrder("""
				{"supplierId": "%s", "lines": [{"productId": "%s", "quantity": 3, "unitPurchasePrice": 14000},
				                               {"productId": "%s", "quantity": 10}]}
				""".formatted(this.supplier, this.frame, this.lens)));

		// Bouton « Réceptionner » du front : corps vide.
		receive(id, "{}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DELIVERED"))
			.andExpect(jsonPath("$.documentStatus").value("RECEIVED"))
			.andExpect(jsonPath("$.deliveredAt").isString())
			.andExpect(jsonPath("$.receivedAmount").value(87000))
			.andExpect(jsonPath("$.lines[0].receivedQuantity").value(3))
			.andExpect(jsonPath("$.lines[0].remainingQuantity").value(0));

		// Le stock du dépôt de la commande a réellement augmenté (bug de l'audit corrigé).
		assertThat(level(this.main, this.frame)).isEqualTo(3);
		assertThat(level(this.main, this.lens)).isEqualTo(10);
		this.mvc.perform(get("/api/products/{id}", this.frame).with(manager()))
			.andExpect(jsonPath("$.stockQuantity").value(3))
			.andExpect(jsonPath("$.purchasePrice").value(14000)); // dernier prix d'achat (RG-PUR-5)
		this.mvc.perform(get("/api/stock/movements").with(manager()).param("productId", this.frame.toString()))
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].type").value("RECEIPT"))
			.andExpect(jsonPath("$.items[0].reason").value("SUPPLY"))
			.andExpect(jsonPath("$.items[0].quantity").value(3))
			.andExpect(jsonPath("$.items[0].unitCost").value(14000))
			.andExpect(jsonPath("$.items[0].sourceType").value("PURCHASE_ORDER"))
			.andExpect(jsonPath("$.items[0].sourceReference").value(matchesPattern("REC-\\d{4}-\\d{6}")))
			.andExpect(jsonPath("$.items[0].createdByUserId").value(this.manager.toString()));

		String movement = this.jdbc.queryForObject(
				"select id::text from stock_movement where product_id = ? and type = 'RECEIPT'", String.class, this.frame);
		this.mvc.perform(get("/api/purchases/orders/{id}/receipts", id).with(manager()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].totalQuantity").value(13))
			.andExpect(jsonPath("$[0].totalAmount").value(87000))
			.andExpect(jsonPath("$[0].receivedByUserId").value(this.manager.toString()))
			.andExpect(jsonPath("$[0].warehouseId").value(this.main.toString()))
			.andExpect(jsonPath("$[0].lines[0].productSku").value("ACH-MON-1"))
			.andExpect(jsonPath("$[0].lines[0].stockMovementId").value(movement));

		// Une commande reçue ne se réceptionne plus : aucun doublon de stock.
		receive(id, "{}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PURCHASE_ORDER_NOT_RECEIVABLE"));
		assertThat(level(this.main, this.frame)).isEqualTo(3);
		checkDeferredConstraints();
	}

	@Test
	void partialReceiptsThenTheRemainder() throws Exception {
		String id = idOf(createOrder("""
				{"supplierId": "%s", "lines": [{"productId": "%s", "quantity": 10}, {"productId": "%s", "quantity": 4}]}
				""".formatted(this.supplier, this.frame, this.lens)));

		receive(id, """
				{"supplierDeliveryNote": "BL-1001", "lines": [{"productId": "%s", "quantity": 4}]}
				""".formatted(this.frame))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andExpect(jsonPath("$.documentStatus").value("PARTIALLY_RECEIVED"))
			.andExpect(jsonPath("$.deliveredAt").doesNotExist())
			.andExpect(jsonPath("$.lastReceiptAt").isString())
			.andExpect(jsonPath("$.receivedAmount").value(60000))
			.andExpect(jsonPath("$.lines[0].receivedQuantity").value(4))
			.andExpect(jsonPath("$.lines[0].remainingQuantity").value(6))
			.andExpect(jsonPath("$.lines[1].receivedQuantity").value(0));
		assertThat(level(this.main, this.frame)).isEqualTo(4);
		assertThat(level(this.main, this.lens)).isZero();

		// Sur-réception refusée, rien n'est écrit.
		receive(id, "{\"lines\": [{\"productId\": \"%s\", \"quantity\": 7}]}".formatted(this.frame))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("RECEIPT_QUANTITY_EXCEEDS_REMAINING"))
			.andExpect(jsonPath("$.details.remaining").value(6))
			.andExpect(jsonPath("$.details.requested").value(7));
		assertThat(level(this.main, this.frame)).isEqualTo(4);
		assertThat(receiptCount(id)).isEqualTo(1);

		// Le reste, en une fois (bouton du front).
		receive(id, "{}")
			.andExpect(jsonPath("$.status").value("DELIVERED"))
			.andExpect(jsonPath("$.documentStatus").value("RECEIVED"))
			.andExpect(jsonPath("$.receivedAmount").value(168000));
		assertThat(level(this.main, this.frame)).isEqualTo(10);
		assertThat(level(this.main, this.lens)).isEqualTo(4);
		assertThat(receiptCount(id)).isEqualTo(2);
		this.mvc.perform(get("/api/purchases/orders/{id}/receipts", id).with(manager()))
			.andExpect(jsonPath("$[0].supplierDeliveryNote").value("BL-1001"))
			.andExpect(jsonPath("$[0].totalQuantity").value(4))
			.andExpect(jsonPath("$[1].totalQuantity").value(10));
		checkDeferredConstraints();
	}

	@Test
	void receiptRequestsAreValidated() throws Exception {
		String id = idOf(createOrder(order(this.frame, "5", null)));

		receive(id, "{\"lines\": [{\"productId\": \"%s\", \"quantity\": 1}]}".formatted(this.lens))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("RECEIPT_PRODUCT_NOT_ORDERED"));
		receive(id, "{\"lines\": [{\"productId\": \"%s\", \"quantity\": 0}]}".formatted(this.frame))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		receive(id, "{\"receivedAt\": \"2999-01-01T00:00:00Z\"}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[*].field", hasItem("receivedAt")));
		receive(UUID.randomUUID().toString(), "{}")
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PURCHASE_ORDER_NOT_FOUND"));
		assertThat(level(this.main, this.frame)).isZero();

		// Date de réception déclarée (ancien nom de champ du front accepté).
		receive(id, "{\"deliveredAtIso\": \"2026-01-15T09:30:00Z\"}").andExpect(status().isOk());
		this.mvc.perform(get("/api/purchases/orders/{id}/receipts", id).with(manager()))
			.andExpect(jsonPath("$[0].receivedAt").value("2026-01-15T09:30:00Z"));
		checkDeferredConstraints();
	}

	// --- Sorties du workflow ---------------------------------------------------------------------

	@Test
	void anOrderWithoutReceiptCanBeCancelledAndThenNeverEntersStock() throws Exception {
		String id = idOf(createOrder(order(this.frame, "5", null)));

		this.mvc.perform(post("/api/purchases/orders/{id}/cancel", id).with(manager())
			.contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"Erreur de fournisseur\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELLED"))
			.andExpect(jsonPath("$.documentStatus").value("CANCELLED"))
			.andExpect(jsonPath("$.closeReason").value("Erreur de fournisseur"))
			.andExpect(jsonPath("$.closedAt").isString());

		receive(id, "{}").andExpect(status().isConflict());
		assertThat(level(this.main, this.frame)).isZero();
	}

	@Test
	void aPartiallyReceivedOrderCanBeClosedButNotCancelled() throws Exception {
		String id = idOf(createOrder(order(this.frame, "5", null)));
		receive(id, "{\"lines\": [{\"productId\": \"%s\", \"quantity\": 2}]}".formatted(this.frame));

		this.mvc.perform(post("/api/purchases/orders/{id}/cancel", id).with(manager()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PURCHASE_ORDER_NOT_CANCELLABLE"));
		this.mvc.perform(post("/api/purchases/orders/{id}/close", id).with(manager())
			.contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"Reliquat abandonné\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DELIVERED"))
			.andExpect(jsonPath("$.documentStatus").value("CLOSED"))
			.andExpect(jsonPath("$.deliveredAt").isString())
			.andExpect(jsonPath("$.lines[0].remainingQuantity").value(3));

		receive(id, "{}").andExpect(status().isConflict());
		assertThat(level(this.main, this.frame)).isEqualTo(2);
		this.mvc.perform(get("/api/purchases/orders").with(manager()).param("status", "CLOSED"))
			.andExpect(jsonPath("$.items[*].id", hasItem(id)));
		checkDeferredConstraints();
	}

	// --- Dépôts et droits ------------------------------------------------------------------------

	@Test
	void stockEntersTheOrderWarehouseWhateverTheCurrentWarehouse() throws Exception {
		String id = idOf(this.mvc.perform(post("/api/purchases/orders").with(manager())
			.header("X-Warehouse-Id", this.depot.toString())
			.contentType(MediaType.APPLICATION_JSON).content(order(this.frame, "6", null)))
			.andExpect(jsonPath("$.warehouseId").value(this.depot.toString())));

		// Réception déclenchée depuis le magasin principal : le stock entre au dépôt commandé.
		receive(id, "{}").andExpect(status().isOk());
		assertThat(level(this.depot, this.frame)).isEqualTo(6);
		assertThat(level(this.main, this.frame)).isZero();

		this.mvc.perform(get("/api/purchases/orders").with(manager()))
			.andExpect(jsonPath("$.items[*].id", org.hamcrest.Matchers.not(hasItem(id))));
		this.mvc.perform(get("/api/purchases/orders").with(manager()).header("X-Warehouse-Id", this.depot.toString()))
			.andExpect(jsonPath("$.items[*].id", hasItem(id)));
		this.mvc.perform(get("/api/suppliers/{id}/purchases", this.supplier).with(manager())
			.param("allWarehouses", "true"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].purchaseOrderId").value(id))
			.andExpect(jsonPath("$.items[0].reference").value(matchesPattern("REC-\\d{4}-\\d{6}")))
			.andExpect(jsonPath("$.items[0].itemsCount").value(6))
			.andExpect(jsonPath("$.items[0].totalAmount").value(90000))
			.andExpect(jsonPath("$.items[0].purchaseDateIso").isString());
		checkDeferredConstraints();
	}

	@Test
	void ordersOfAnotherWarehouseAreInvisibleToAUserWithoutAccess() throws Exception {
		String id = idOf(this.mvc.perform(post("/api/purchases/orders").with(manager())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"supplierId": "%s", "warehouseId": "%s", "lines": [{"productId": "%s", "quantity": 1}]}
					""".formatted(this.supplier, this.depot, this.frame))));

		// Sans warehouse:all ni affectation : seul le magasin principal est accessible.
		RequestPostProcessor clerk = actor(Permissions.PURCHASE_READ, Permissions.PURCHASE_CREATE,
				Permissions.PURCHASE_RECEIVE, Permissions.WAREHOUSE_READ);
		this.mvc.perform(get("/api/purchases/orders/{id}", id).with(clerk)).andExpect(status().isNotFound());
		this.mvc.perform(post("/api/purchases/orders/{id}/receive", id).with(clerk)
			.contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isNotFound());
		this.mvc.perform(post("/api/purchases/orders").with(clerk).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"supplierId": "%s", "warehouseId": "%s", "lines": [{"productId": "%s", "quantity": 1}]}
					""".formatted(this.supplier, this.depot, this.frame)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("WAREHOUSE_ACCESS_DENIED"));
		assertThat(level(this.depot, this.frame)).isZero();
	}

	@Test
	void permissionsSeparateOrderingAndReceiving() throws Exception {
		String id = idOf(createOrder(order(this.frame, "2", null)));

		RequestPostProcessor reader = actor(Permissions.PURCHASE_READ);
		this.mvc.perform(get("/api/purchases/orders/{id}", id).with(reader)).andExpect(status().isOk());
		this.mvc.perform(post("/api/purchases/orders").with(reader).contentType(MediaType.APPLICATION_JSON)
			.content(order(this.frame, "1", null)))
			.andExpect(status().isForbidden());

		RequestPostProcessor buyer = actor(Permissions.PURCHASE_READ, Permissions.PURCHASE_CREATE);
		this.mvc.perform(post("/api/purchases/orders/{id}/receive", id).with(buyer)
			.contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isForbidden());
		assertThat(level(this.main, this.frame)).isZero();

		RequestPostProcessor cashier = actor(Permissions.SALE_CREATE, Permissions.STOCK_READ);
		this.mvc.perform(get("/api/purchases/orders").with(cashier)).andExpect(status().isForbidden());
	}

	// --- Outils ------------------------------------------------------------------------------------

	/** Déclenche les contraintes différées de V015 avant le rollback du test. */
	private void checkDeferredConstraints() {
		this.jdbc.execute("set constraints all immediate");
	}

	private UUID product(String sku, String name, int purchasePrice) {
		return this.jdbc.queryForObject("""
				insert into product (sku, name, category_id, purchase_price, retail_price, wholesale_price)
				values (?, ?, (select id from category where name = 'Montures'), ?, ?, ?) returning id""", UUID.class,
				sku, name, purchasePrice, purchasePrice * 2, purchasePrice * 2);
	}

	private String order(UUID productId, String quantity, String unitPrice) {
		return """
				{"supplierId": "%s", "lines": [{"productId": "%s", "quantity": %s%s}]}
				""".formatted(this.supplier, productId, quantity,
				unitPrice != null ? ", \"unitPurchasePrice\": " + unitPrice : "");
	}

	private ResultActions createOrder(String body) throws Exception {
		return this.mvc.perform(post("/api/purchases/orders").with(manager()).contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private ResultActions receive(String orderId, String body) throws Exception {
		return this.mvc.perform(post("/api/purchases/orders/{id}/receive", orderId).with(manager())
			.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private int level(UUID warehouseId, UUID productId) {
		return this.jdbc.queryForList("select quantity from stock_level where warehouse_id = ? and product_id = ?",
				Integer.class, warehouseId, productId).stream().findFirst().orElse(0);
	}

	private int receiptCount(String orderId) {
		return this.jdbc.queryForObject("select count(*) from goods_receipt where purchase_order_id = ?::uuid",
				Integer.class, orderId);
	}

	private RequestPostProcessor manager() {
		return actor(Permissions.ALL.toArray(String[]::new));
	}

	private RequestPostProcessor actor(String... permissions) {
		return authentication(StockFixtures.authentication(this.manager, permissions));
	}

	private String idOf(ResultActions result) throws Exception {
		return this.json.readTree(result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
			.get("id")
			.asString();
	}

}
