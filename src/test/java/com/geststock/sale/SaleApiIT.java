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
	void theSaleLeavesTheCurrentWarehouseOnly() throws Exception {
		stockIn(this.depot, this.frame, 3);

		// Dépôt imposé par le corps, différent du dépôt courant (principal) : refusé, rien n'est écrit.
		sell(manager(), """
				{"warehouseId": "%s", "customerId": "%s", "paidAmount": 0, "items": [{"productId": "%s", "quantity": 1}]}
				""".formatted(this.depot, this.customer, this.frame))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("WAREHOUSE_MISMATCH"));
		assertThat(level(this.depot, this.frame)).isEqualTo(3);
		assertThat(level(this.main, this.frame)).isEqualTo(10);

		// La caisse du vendeur est ouverte au magasin principal : pas d'encaissement au dépôt.
		sellAt(this.depot, """
				{"paymentMethod": "CASH", "items": [{"productId": "%s", "quantity": 1}]}
				""".formatted(this.frame))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CASH_SESSION_OTHER_WAREHOUSE"));
		assertThat(level(this.depot, this.frame)).isEqualTo(3);

		// Vente à crédit (rien à encaisser) : possible depuis le dépôt ; le corps peut confirmer le dépôt courant.
		String id = idOf(sellAt(this.depot, """
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

	@Test
	void aSaleIsCancelledAndReturnedFromItsWarehouseOnly() throws Exception {
		stockIn(this.depot, this.frame, 3);
		String id = idOf(sellAt(this.depot, """
				{"customerId": "%s", "paidAmount": 0, "items": [{"productId": "%s", "quantity": 2}]}
				""".formatted(this.customer, this.frame)));

		// Gestionnaire positionné sur le magasin principal : la vente du dépôt reste lisible…
		this.mvc.perform(get("/api/sales/{id}", id).with(manager())).andExpect(status().isOk());
		// … mais ni annulée ni retournée depuis un autre dépôt ; rien ne bouge.
		cancel(id, "{\"reason\": \"Erreur\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("WAREHOUSE_MISMATCH"));
		this.mvc.perform(post("/api/sales/{id}/returns", id).with(manager()).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"settlement": "ACCOUNT_CREDIT", "reason": "Taille", "items": [{"productId": "%s", "quantity": 1,
					"condition": "RESELLABLE"}]}""".formatted(this.frame)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("WAREHOUSE_MISMATCH"));
		assertThat(level(this.depot, this.frame)).isEqualTo(1);
		assertThat(this.jdbc.queryForObject("select status from sale where id = ?::uuid", String.class, id))
			.isEqualTo("COMPLETED");
		assertThat(this.jdbc.queryForObject("select count(*) from customer_return where sale_id = ?::uuid",
				Integer.class, id)).isZero();

		// Depuis le dépôt de la vente : annulation acceptée.
		this.mvc.perform(post("/api/sales/{id}/cancel", id).with(manager()).header("X-Warehouse-Id", this.depot.toString())
			.contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"Erreur\"}"))
			.andExpect(status().isOk());
		assertThat(level(this.depot, this.frame)).isEqualTo(3);
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
		this.mvc.perform(get("/api/customers/{id}/sales", this.customer).with(manager()).param("q", "vte-"))
			.andExpect(jsonPath("$.total").value(2));
		this.mvc.perform(get("/api/customers/{id}/sales", this.customer).with(manager()).param("q", "introuvable"))
			.andExpect(jsonPath("$.total").value(0));
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
	void aCustomerSalesHistoryOnlyShowsTheWarehousesTheUserMaySee() throws Exception {
		stockIn(this.depot, this.frame, 2);
		String inStore = idOf(sell(manager(), """
				{"customerId": "%s", "paymentMethod": "CASH", "items": [{"productId": "%s", "quantity": 1}]}"""
			.formatted(this.customer, this.frame)));
		String inDepot = idOf(sellAt(this.depot, """
				{"customerId": "%s", "paidAmount": 0, "items": [{"productId": "%s", "quantity": 1}]}"""
			.formatted(this.customer, this.frame)));

		// Caissier sans warehouse:all, rattaché au magasin principal : la vente du dépôt lui reste invisible.
		RequestPostProcessor cashier = actor(Permissions.SALE_READ, Permissions.CUSTOMER_READ);
		this.mvc.perform(get("/api/customers/{id}/sales", this.customer).with(cashier))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].id").value(inStore));
		this.mvc.perform(get("/api/sales/{id}", inDepot).with(cashier)).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/customers/{id}/sales", this.customer).with(manager()))
			.andExpect(jsonPath("$.total").value(2));
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

	// --- Client saisi à la caisse ------------------------------------------------------------------

	@Test
	void aCustomerUnknownToTheBaseIsCreatedWithTheSaleAndReusedNextTime() throws Exception {
		String first = idOf(sell(manager(), """
				{"paymentMethod": "CASH", "newCustomer": {"name": "Koffi Yao", "phone": "+225 07 01 02 03"},
				 "items": [{"productId": "%s", "quantity": 1}]}""".formatted(this.frame))
			.andExpect(jsonPath("$.customerId").isString())
			.andExpect(jsonPath("$.customerName").value("Koffi Yao")));
		UUID created = this.jdbc.queryForObject("select customer_id from sale where id = ?::uuid", UUID.class, first);
		assertThat(this.jdbc.queryForMap("select name, phone, credit_limit, active from customer where id = ?", created))
			.containsEntry("name", "Koffi Yao")
			.containsEntry("phone", "+225 07 01 02 03")
			.containsEntry("active", true);
		assertThat(this.jdbc.queryForObject("select credit_limit from customer where id = ?", java.math.BigDecimal.class,
				created)).as("client comptant").isZero();

		// Même client ressaisi autrement (casse, espaces, séparateurs) : retrouvé, pas recréé.
		sell(manager(), """
				{"paymentMethod": "CASH", "newCustomer": {"name": "  koffi YAO ", "phone": "+225-07.01.02.03"},
				 "items": [{"productId": "%s", "quantity": 1}]}""".formatted(this.frame))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.customerId").value(created.toString()));
		// Homonyme avec un autre numéro : un autre client.
		sell(manager(), """
				{"paymentMethod": "CASH", "newCustomer": {"name": "Koffi Yao", "phone": "+225 05 99 99 99"},
				 "items": [{"productId": "%s", "quantity": 1}]}""".formatted(this.frame))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.customerId").value(org.hamcrest.Matchers.not(created.toString())));
		assertThat(this.jdbc.queryForObject("select count(*) from customer where lower(name) = 'koffi yao'",
				Integer.class)).isEqualTo(2);
		assertThat(this.jdbc.queryForObject("""
				select count(*) from audit_log where action = 'CREATE' and entity_type = 'CUSTOMER' and entity_id = ?""",
				Integer.class, created.toString())).as("création tracée").isEqualTo(1);
		checkDeferredConstraints();
	}

	@Test
	void newCustomerRequestsAreValidatedAndRequireTheCreatePermission() throws Exception {
		sell(manager(), """
				{"paymentMethod": "CASH", "customerId": "%s", "newCustomer": {"name": "Double"},
				 "items": [{"productId": "%s", "quantity": 1}]}""".formatted(this.customer, this.frame))
			.andExpect(status().isBadRequest());
		sell(manager(), """
				{"paymentMethod": "CASH", "newCustomer": {"name": " ", "phone": "appeler"},
				 "items": [{"productId": "%s", "quantity": 1}]}""".formatted(this.frame))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[*].field", hasItem("newCustomer.name")))
			.andExpect(jsonPath("$.errors[*].field", hasItem("newCustomer.phone")));

		RequestPostProcessor seller = actor(Permissions.SALE_CREATE, Permissions.SALE_READ, Permissions.CUSTOMER_READ);
		sell(seller, """
				{"paymentMethod": "CASH", "newCustomer": {"name": "Sans Droit"},
				 "items": [{"productId": "%s", "quantity": 1}]}""".formatted(this.frame))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("CUSTOMER_CREATE_FORBIDDEN"));
		assertThat(level(this.main, this.frame)).as("aucune sortie de stock").isEqualTo(10);
	}

	// --- Montant reçu et monnaie rendue -----------------------------------------------------------

	@Test
	void cashReceivedAndChangeGivenAreKeptForVerification() throws Exception {
		String id = idOf(sell(manager(), """
				{"paymentMethod": "CASH", "cashReceived": 50000,
				 "items": [{"productId": "%s", "quantity": 1}]}""".formatted(this.frame))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.paidAmount").value(35000))
			.andExpect(jsonPath("$.cashReceived").value(50000))
			.andExpect(jsonPath("$.changeGiven").value(15000)));

		// Enregistrés en base (lecture JDBC : la relecture HTTP de ce test transactionnel passe par le
		// cache d'Hibernate et ne verrait pas une valeur restée en mémoire).
		java.util.Map<String, Object> stored = this.jdbc
			.queryForMap("select cash_received, change_given from sale where id = ?::uuid", id);
		assertThat((java.math.BigDecimal) stored.get("cash_received")).isEqualByComparingTo("50000");
		assertThat((java.math.BigDecimal) stored.get("change_given")).isEqualByComparingTo("15000");

		// Relu pour vérification ; la caisse n'encaisse que le montant payé.
		this.mvc.perform(get("/api/sales/{id}", id).with(manager()))
			.andExpect(jsonPath("$.cashReceived").value(50000))
			.andExpect(jsonPath("$.changeGiven").value(15000));
		assertThat(this.jdbc.queryForObject("select amount from cash_entry where source_id = ?::uuid", java.math.BigDecimal.class,
				id)).isEqualByComparingTo("35000");

		// Montant non saisi : rien n'est enregistré (ventes antérieures, autres moyens de paiement).
		this.mvc.perform(get("/api/sales/{id}", idOf(sell(manager(), """
				{"paymentMethod": "CASH", "items": [{"productId": "%s", "quantity": 1}]}""".formatted(this.frame)))).with(manager()))
			.andExpect(jsonPath("$.cashReceived").isEmpty())
			.andExpect(jsonPath("$.changeGiven").isEmpty());

		// Immuables : la base refuse toute modification (en dernier : l'erreur interrompt la transaction du test).
		org.assertj.core.api.Assertions.assertThatThrownBy(
				() -> this.jdbc.update("update sale set change_given = 0 where id = ?::uuid", id))
			.hasMessageContaining("non modifiables");
	}

	@Test
	void partialPaymentChangeIsComputedOnTheAmountPaidNow() throws Exception {
		sell(manager(), """
				{"customerId": "%s", "paymentMethod": "CASH", "paidAmount": 20000, "cashReceived": 25000,
				 "items": [{"productId": "%s", "quantity": 1}]}""".formatted(this.customer, this.frame))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.paidAmount").value(20000))
			.andExpect(jsonPath("$.creditAmount").value(15000))
			.andExpect(jsonPath("$.changeGiven").value(5000));
	}

	@Test
	void anInvalidCashReceivedIsRefusedAndNothingIsSold() throws Exception {
		int before = saleCount();
		// Inférieur au montant payé.
		sell(manager(), """
				{"paymentMethod": "CASH", "cashReceived": 30000, "items": [{"productId": "%s", "quantity": 1}]}"""
			.formatted(this.frame))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_CASH_RECEIVED"));
		// Paiement autre qu'en espèces.
		sell(manager(), """
				{"paymentMethod": "MOBILE_MONEY", "cashReceived": 50000, "items": [{"productId": "%s", "quantity": 1}]}"""
			.formatted(this.frame))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_CASH_RECEIVED"));
		// Négatif.
		sell(manager(), """
				{"paymentMethod": "CASH", "cashReceived": -1, "items": [{"productId": "%s", "quantity": 1}]}"""
			.formatted(this.frame))
			.andExpect(status().isBadRequest());
		assertThat(saleCount()).isEqualTo(before);
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

	/** Vente du gestionnaire, le dépôt courant étant {@code warehouseId} (en-tête X-Warehouse-Id). */
	private ResultActions sellAt(UUID warehouseId, String body) throws Exception {
		return this.mvc.perform(post("/api/sales").with(manager()).header("X-Warehouse-Id", warehouseId.toString())
			.contentType(MediaType.APPLICATION_JSON).content(body));
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
