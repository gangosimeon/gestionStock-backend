package com.geststock.sale;

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
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ventes de bout en bout sur PostgreSQL : prix serveur, remise, stock, crédit, annulation,
 * recherche, droits et audit. Chaque test s'exécute dans une transaction annulée ; les
 * contraintes différées de V017 sont forcées avant la fin ({@code SET CONSTRAINTS ALL IMMEDIATE}).
 * Les refus survenant après une première écriture (stock insuffisant sur une ligne, plafond de
 * crédit) se vérifient en transactions réelles : {@link SaleTransactionIT}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@Transactional
class SaleApiIT {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private JsonMapper json;

	private UUID manager;

	private UUID main;

	private UUID depot;

	private UUID frame;

	private UUID lens;

	private UUID customer;

	@BeforeEach
	void setUp() throws Exception {
		this.manager = this.jdbc.queryForObject(
				"insert into app_user (username, last_name) values ('sale_api_user', 'Vendeur') returning id", UUID.class);
		this.main = this.jdbc.queryForObject("select id from warehouse where main", UUID.class);
		this.depot = this.jdbc.queryForObject(
				"insert into warehouse (code, name, type) values ('ZZ-VTE', 'Dépôt API ventes', 'WAREHOUSE') returning id",
				UUID.class);
		this.frame = product("VTE-MON-1", "Monture vente", 15000, 35000, 28000);
		this.lens = product("VTE-LEN-1", "Lentilles vente", 4500, 10000, 8500);
		this.customer = this.jdbc.queryForObject("""
				insert into customer (code, name, credit_limit, price_category)
				values ('CLI-VTE-API', 'Clinique Vente', 100000, 'RETAIL') returning id""", UUID.class);
		stockIn(this.main, this.frame, 10);
		stockIn(this.main, this.lens, 5);
		// Une vente payée s'encaisse dans la caisse ouverte du vendeur (V018).
		this.mvc.perform(post("/api/cash-register/open").with(manager()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"openingBalance\": 50000}"))
			.andExpect(status().isCreated());
	}

	// --- Prix serveur ---------------------------------------------------------------------------

	@Test
	void pricesSentByTheFrontAreIgnoredAndRecomputedByTheServer() throws Exception {
		// Charge utile du POS avec des prix falsifiés (unitPrice, purchasePrice, total) : ignorés.
		String id = idOf(sell(manager(), """
				{"type": "RETAIL", "paymentMethod": "CASH", "total": 2, "profit": 2, "items": [
				  {"productId": "%s", "quantity": 2, "unitPrice": 1, "purchasePrice": 0}]}
				""".formatted(this.frame))
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", startsWith("/api/sales/")))
			.andExpect(jsonPath("$.number").value(matchesPattern("VTE-\\d{4}-\\d{6}")))
			.andExpect(jsonPath("$.status").value("COMPLETED"))
			.andExpect(jsonPath("$.type").value("RETAIL"))
			.andExpect(jsonPath("$.warehouseId").value(this.main.toString()))
			.andExpect(jsonPath("$.items[0].unitPrice").value(35000))
			.andExpect(jsonPath("$.items[0].purchasePrice").value(15000))
			.andExpect(jsonPath("$.items[0].lineTotal").value(70000))
			.andExpect(jsonPath("$.total").value(70000))
			.andExpect(jsonPath("$.paidAmount").value(70000))
			.andExpect(jsonPath("$.creditAmount").value(0))
			.andExpect(jsonPath("$.costTotal").value(30000))
			.andExpect(jsonPath("$.profit").value(40000))
			.andExpect(jsonPath("$.paymentMethod").value("CASH"))
			.andExpect(jsonPath("$.createdByUserId").value(this.manager.toString()))
			.andExpect(jsonPath("$.createdAt").isString()));

		assertThat(level(this.main, this.frame)).isEqualTo(8);
		this.mvc.perform(get("/api/stock/movements").with(manager()).param("productId", this.frame.toString())
			.param("sort", "createdAt,desc"))
			.andExpect(jsonPath("$.items[0].type").value("SALE"))
			.andExpect(jsonPath("$.items[0].quantity").value(-2))
			.andExpect(jsonPath("$.items[0].sourceType").value("SALE"))
			.andExpect(jsonPath("$.items[0].sourceId").value(id))
			.andExpect(jsonPath("$.items[0].unitCost").value(15000));
		assertThat(this.jdbc.queryForObject("""
				select count(*) from audit_log
				where action = 'CREATE' and entity_type = 'SALE' and entity_id = ? and user_id = ? and status = 'SUCCESS'""",
				Integer.class, id, this.manager)).as("audit de la vente").isEqualTo(1);
		this.mvc.perform(get("/api/cash-register/current").with(manager()))
			.andExpect(jsonPath("$.cashSalesTotal").value(70000))
			.andExpect(jsonPath("$.expectedCash").value(120000));
		checkDeferredConstraints();
	}

	@Test
	void aFalsifiedTotalCannotTurnIntoAnUnpaidSale() throws Exception {
		// Le front paie « son » total (2) : le reste dû réel exige un client, la vente est refusée.
		sell(manager(), """
				{"paymentMethod": "CASH", "paidAmount": 2, "items": [{"productId": "%s", "quantity": 2, "unitPrice": 1}]}
				""".formatted(this.frame))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("CUSTOMER_REQUIRED_FOR_CREDIT"));
		assertThat(level(this.main, this.frame)).isEqualTo(10);
		assertThat(saleCount()).isZero();
	}

	@Test
	void discountsAreCappedAndTheWholesaleTariffComesFromTheCustomer() throws Exception {
		sell(manager(), "{\"paymentMethod\": \"CASH\", \"items\": [{\"productId\": \"%s\", \"quantity\": 2, \"discountPercent\": 10}]}"
			.formatted(this.frame))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.subtotal").value(70000))
			.andExpect(jsonPath("$.discountTotal").value(7000))
			.andExpect(jsonPath("$.items[0].discountPercent").value(10))
			.andExpect(jsonPath("$.total").value(63000))
			.andExpect(jsonPath("$.profit").value(33000));

		sell(manager(), "{\"paymentMethod\": \"CASH\", \"items\": [{\"productId\": \"%s\", \"quantity\": 1, \"discountPercent\": 25}]}"
			.formatted(this.frame))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("DISCOUNT_NOT_ALLOWED"))
			.andExpect(jsonPath("$.details.maxDiscountPercent").value(20));

		this.jdbc.update("update customer set price_category = 'WHOLESALE' where id = ?", this.customer);
		sell(manager(), """
				{"customerId": "%s", "paymentMethod": "MOBILE_MONEY", "items": [{"productId": "%s", "quantity": 1}]}
				""".formatted(this.customer, this.lens))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.type").value("WHOLESALE"))
			.andExpect(jsonPath("$.items[0].unitPrice").value(8500))
			.andExpect(jsonPath("$.customerName").value("Clinique Vente"));
		checkDeferredConstraints();
	}

	// --- Stock ----------------------------------------------------------------------------------

	@Test
	void theSaleLeavesTheRequestedWarehouseOnly() throws Exception {
		stockIn(this.depot, this.frame, 3);

		// La caisse du vendeur est ouverte au magasin principal : pas d'encaissement au dépôt.
		sell(manager(), """
				{"warehouseId": "%s", "paymentMethod": "CASH", "items": [{"productId": "%s", "quantity": 1}]}
				""".formatted(this.depot, this.frame))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CASH_SESSION_OTHER_WAREHOUSE"));
		assertThat(level(this.depot, this.frame)).isEqualTo(3);

		// Vente à crédit (rien à encaisser) : possible depuis le dépôt.
		String id = idOf(sell(manager(), """
				{"warehouseId": "%s", "customerId": "%s", "paidAmount": 0, "items": [{"productId": "%s", "quantity": 2}]}
				""".formatted(this.depot, this.customer, this.frame)));
		stockOut(this.depot, this.frame, 1);

		assertThat(level(this.depot, this.frame)).isZero();
		assertThat(level(this.main, this.frame)).isEqualTo(10);
		this.mvc.perform(get("/api/sales").with(manager())).andExpect(jsonPath("$.items[*].id", not(hasItem(id))));
		this.mvc.perform(get("/api/sales").with(manager()).header("X-Warehouse-Id", this.depot.toString()))
			.andExpect(jsonPath("$.items[*].id", hasItem(id)));
		this.mvc.perform(get("/api/sales").with(manager()).param("allWarehouses", "true"))
			.andExpect(jsonPath("$.items[*].id", hasItem(id)));
		checkDeferredConstraints();
	}

	// --- Règlement et crédit ----------------------------------------------------------------------

	@Test
	void aPartlyPaidSaleChargesTheCustomerAccountWithinItsCreditLimit() throws Exception {
		idOf(sell(manager(), """
				{"customerId": "%s", "paymentMethod": "CASH", "paidAmount": 20000,
				 "items": [{"productId": "%s", "quantity": 2}]}
				""".formatted(this.customer, this.frame))
			.andExpect(jsonPath("$.paidAmount").value(20000))
			.andExpect(jsonPath("$.creditAmount").value(50000)));
		this.mvc.perform(get("/api/customers/{id}/account", this.customer).with(manager()))
			.andExpect(jsonPath("$.balance").value(50000))
			.andExpect(jsonPath("$.availableCredit").value(50000));

		// Vente entièrement à crédit, sans mode de paiement : 50 000 de plus atteignent le plafond.
		// (Le refus au-delà du plafond, avec annulation du stock, est vérifié par SaleTransactionIT.)
		sell(manager(), """
				{"customerId": "%s", "paidAmount": 0, "items": [{"productId": "%s", "quantity": 5}]}
				""".formatted(this.customer, this.lens))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.paymentMethod").doesNotExist())
			.andExpect(jsonPath("$.creditAmount").value(50000));
		this.mvc.perform(get("/api/customers/{id}/sales", this.customer).with(manager()))
			.andExpect(jsonPath("$.total").value(2));
		checkDeferredConstraints();
	}

	@Test
	void paymentsAreValidated() throws Exception {
		sell(manager(), "{\"paymentMethod\": \"CASH\", \"paidAmount\": 40000, \"items\": [{\"productId\": \"%s\", \"quantity\": 1}]}"
			.formatted(this.frame))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PAID_AMOUNT_EXCEEDS_TOTAL"));
		sell(manager(), "{\"items\": [{\"productId\": \"%s\", \"quantity\": 1}]}".formatted(this.frame))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PAYMENT_METHOD_REQUIRED"));
		sell(manager(), "{\"paymentMethod\": \"CHEQUE\", \"items\": [{\"productId\": \"%s\", \"quantity\": 1}]}"
			.formatted(this.frame))
			.andExpect(status().isBadRequest());
		assertThat(saleCount()).isZero();
	}

	// --- Annulation -----------------------------------------------------------------------------

	@Test
	void cancellationRestocksAndCancelsTheCredit() throws Exception {
		String id = idOf(sell(manager(), """
				{"customerId": "%s", "paymentMethod": "CASH", "paidAmount": 10000,
				 "items": [{"productId": "%s", "quantity": 2}, {"productId": "%s", "quantity": 1}]}
				""".formatted(this.customer, this.frame, this.lens)));
		assertThat(level(this.main, this.frame)).isEqualTo(8);

		cancel(id, "{}").andExpect(status().isBadRequest());
		cancel(id, "{\"reason\": \"Erreur de saisie\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELLED"))
			.andExpect(jsonPath("$.cancellationReason").value("Erreur de saisie"))
			.andExpect(jsonPath("$.cancelledAt").isString());

		assertThat(level(this.main, this.frame)).isEqualTo(10);
		assertThat(level(this.main, this.lens)).isEqualTo(5);
		this.mvc.perform(get("/api/customers/{id}/account", this.customer).with(manager()))
			.andExpect(jsonPath("$.balance").value(0));
		// Les 10 000 encaissés sont remboursés depuis la caisse.
		this.mvc.perform(get("/api/cash-register/current").with(manager()))
			.andExpect(jsonPath("$.cashSalesTotal").value(0))
			.andExpect(jsonPath("$.expectedCash").value(50000));
		assertThat(this.jdbc.queryForObject(
				"select count(*) from audit_log where action = 'CANCEL' and entity_type = 'SALE' and entity_id = ? and status = 'SUCCESS'",
				Integer.class, id)).isEqualTo(1);
		cancel(id, "{\"reason\": \"Deux fois\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SALE_NOT_CANCELLABLE"));
		checkDeferredConstraints();
	}

	// --- Validation, recherche, droits -------------------------------------------------------------

	@Test
	void requestsAreValidated() throws Exception {
		sell(manager(), "{\"paymentMethod\": \"CASH\", \"items\": []}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		sell(manager(), "{\"paymentMethod\": \"CASH\", \"items\": [{\"productId\": \"%s\", \"quantity\": 0}]}"
			.formatted(this.frame))
			.andExpect(status().isBadRequest());
		sell(manager(), """
				{"paymentMethod": "CASH", "items": [{"productId": "%s", "quantity": 1}, {"productId": "%s", "quantity": 1}]}
				""".formatted(this.frame, this.frame))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("SALE_DUPLICATE_PRODUCT"));
		sell(manager(), "{\"paymentMethod\": \"CASH\", \"items\": [{\"productId\": \"%s\", \"quantity\": 1}]}"
			.formatted(UUID.randomUUID()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));

		UUID unpriced = product("VTE-SANS-PRIX", "Sans prix", 100, 0, 0);
		stockIn(this.main, unpriced, 1);
		sell(manager(), "{\"paymentMethod\": \"CASH\", \"items\": [{\"productId\": \"%s\", \"quantity\": 1}]}"
			.formatted(unpriced))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("PRODUCT_PRICE_NOT_SET"));

		this.mvc.perform(delete("/api/products/{id}", this.lens).with(manager())).andExpect(status().isOk());
		sell(manager(), "{\"paymentMethod\": \"CASH\", \"items\": [{\"productId\": \"%s\", \"quantity\": 1}]}"
			.formatted(this.lens))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("PRODUCT_INACTIVE"));

		this.mvc.perform(delete("/api/customers/{id}", this.customer).with(manager())).andExpect(status().isOk());
		sell(manager(), """
				{"customerId": "%s", "paymentMethod": "CASH", "items": [{"productId": "%s", "quantity": 1}]}
				""".formatted(this.customer, this.frame))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("CUSTOMER_INACTIVE"));
		assertThat(saleCount()).isZero();
	}

	@Test
	void salesAreSearchableAndPaginated() throws Exception {
		String first = idOf(sell(manager(), "{\"paymentMethod\": \"CASH\", \"items\": [{\"productId\": \"%s\", \"quantity\": 1}]}"
			.formatted(this.frame)));
		idOf(sell(manager(), """
				{"customerId": "%s", "paymentMethod": "BANK_TRANSFER", "items": [{"productId": "%s", "quantity": 1}]}
				""".formatted(this.customer, this.lens)));
		String number = this.jdbc.queryForObject("select number from sale where id = ?::uuid", String.class, first);

		this.mvc.perform(get("/api/sales").with(manager()))
			.andExpect(jsonPath("$.total").value(2));
		this.mvc.perform(get("/api/sales").with(manager()).param("q", number))
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].id").value(first));
		this.mvc.perform(get("/api/sales").with(manager()).param("q", "clinique"))
			.andExpect(jsonPath("$.total").value(1));
		this.mvc.perform(get("/api/sales").with(manager()).param("paymentMethod", "BANK_TRANSFER"))
			.andExpect(jsonPath("$.total").value(1));
		this.mvc.perform(get("/api/sales").with(manager()).param("from", "2000-01-01T00:00:00Z")
			.param("to", "2001-01-01T00:00:00Z"))
			.andExpect(jsonPath("$.total").value(0));
		this.mvc.perform(get("/api/sales").with(manager()).param("page", "0").param("size", "1"))
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.totalPages").value(2));
		this.mvc.perform(get("/api/sales/{id}", first).with(manager()))
			.andExpect(jsonPath("$.items[0].productSku").value("VTE-MON-1"));
		this.mvc.perform(get("/api/sales/{id}", UUID.randomUUID()).with(manager()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("SALE_NOT_FOUND"));
		checkDeferredConstraints();
	}

	@Test
	void aCashierSellsButCannotCancel() throws Exception {
		RequestPostProcessor cashier = actor(Permissions.SALE_READ, Permissions.SALE_CREATE, Permissions.PRODUCT_READ,
				Permissions.CUSTOMER_READ);
		String id = idOf(sell(cashier, "{\"paymentMethod\": \"CASH\", \"items\": [{\"productId\": \"%s\", \"quantity\": 1}]}"
			.formatted(this.frame)));

		this.mvc.perform(post("/api/sales/{id}/cancel", id).with(cashier).contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\": \"Essai\"}"))
			.andExpect(status().isForbidden());
		sell(actor(Permissions.STOCK_READ), "{\"paymentMethod\": \"CASH\", \"items\": [{\"productId\": \"%s\", \"quantity\": 1}]}"
			.formatted(this.frame))
			.andExpect(status().isForbidden());
		assertThat(level(this.main, this.frame)).isEqualTo(9);
	}

	// --- Outils ------------------------------------------------------------------------------------

	/** Déclenche les contraintes différées de V017 avant le rollback du test. */
	private void checkDeferredConstraints() {
		this.jdbc.execute("set constraints all immediate");
	}

	private UUID product(String sku, String name, int purchasePrice, int retailPrice, int wholesalePrice) {
		return this.jdbc.queryForObject("""
				insert into product (sku, name, category_id, purchase_price, retail_price, wholesale_price)
				values (?, ?, (select id from category where name = 'Montures'), ?, ?, ?) returning id""", UUID.class,
				sku, name, purchasePrice, retailPrice, wholesalePrice);
	}

	private void stockIn(UUID warehouseId, UUID productId, int quantity) throws Exception {
		this.mvc.perform(post("/api/stock/movements").with(manager()).header("X-Warehouse-Id", warehouseId.toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"productId\": \"%s\", \"quantity\": %d, \"reason\": \"SUPPLY\"}".formatted(productId, quantity)))
			.andExpect(status().isCreated());
	}

	private void stockOut(UUID warehouseId, UUID productId, int quantity) throws Exception {
		this.mvc.perform(post("/api/stock/movements").with(manager()).header("X-Warehouse-Id", warehouseId.toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"productId\": \"%s\", \"quantity\": %d, \"reason\": \"LOSS\"}".formatted(productId, -quantity)))
			.andExpect(status().isCreated());
	}

	private ResultActions sell(RequestPostProcessor actor, String body) throws Exception {
		return this.mvc.perform(post("/api/sales").with(actor).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions cancel(String id, String body) throws Exception {
		return this.mvc.perform(post("/api/sales/{id}/cancel", id).with(manager()).contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private int level(UUID warehouseId, UUID productId) {
		return this.jdbc.queryForList("select quantity from stock_level where warehouse_id = ? and product_id = ?",
				Integer.class, warehouseId, productId).stream().findFirst().orElse(0);
	}

	private int saleCount() {
		return this.jdbc.queryForObject("select count(*) from sale", Integer.class);
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
