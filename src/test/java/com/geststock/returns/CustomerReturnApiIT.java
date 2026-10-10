package com.geststock.returns;

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
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Retours clients de bout en bout sur PostgreSQL : montants au prix net, remise en stock des
 * seuls articles revendables, remboursement en caisse ou avoir sur compte, cumul plafonné au
 * vendu, vente conservée et non annulable ensuite, droits et audit. Chaque test s'exécute dans
 * une transaction annulée ; les contraintes différées de V020 sont forcées avant la fin. Les
 * refus après première écriture et la concurrence : {@link CustomerReturnTransactionIT}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@Transactional
class CustomerReturnApiIT {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private JsonMapper json;

	private UUID manager;

	private UUID main;

	private UUID frame;

	private UUID lens;

	private UUID customer;

	@BeforeEach
	void setUp() throws Exception {
		this.manager = this.jdbc.queryForObject(
				"insert into app_user (username, last_name) values ('return_api_user', 'Retours') returning id", UUID.class);
		this.main = this.jdbc.queryForObject("select id from warehouse where main", UUID.class);
		this.frame = product("RET-MON-1", "Monture retour", 15000, 35000);
		this.lens = product("RET-LEN-1", "Lentilles retour", 4500, 10000);
		this.customer = this.jdbc.queryForObject("""
				insert into customer (code, name, credit_limit) values ('CLI-RET-API', 'Client retours', 200000)
				returning id""", UUID.class);
		stockIn(this.frame, 10);
		stockIn(this.lens, 10);
		this.mvc.perform(post("/api/cash-register/open").with(manager()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"openingBalance\": 50000}"))
			.andExpect(status().isCreated());
	}

	// --- Remboursement ------------------------------------------------------------------------------

	@Test
	void aPartialReturnIsRefundedAtTheNetPriceAndRestocksOnlyResellableItems() throws Exception {
		// 3 montures à 35 000 remisées de 10 % = 94 500, plus 2 lentilles = 20 000 ; payé comptant.
		String sale = idOf(sell("""
				{"paymentMethod": "CASH", "items": [{"productId": "%s", "quantity": 3, "discountPercent": 10},
				                                    {"productId": "%s", "quantity": 2}]}
				""".formatted(this.frame, this.lens)));
		assertThat(level(this.frame)).isEqualTo(7);

		String first = idOf(giveBack(sale, """
				{"settlement": "REFUND", "reason": "Ne convient pas", "items": [
				  {"productId": "%s", "quantity": 1, "condition": "RESELLABLE"},
				  {"productId": "%s", "quantity": 1, "condition": "DEFECTIVE"}]}
				""".formatted(this.frame, this.lens))
			.andExpect(header().string("Location", startsWith("/api/returns/")))
			.andExpect(jsonPath("$.number").value(matchesPattern("RET-\\d{4}-\\d{6}")))
			.andExpect(jsonPath("$.saleId").value(sale))
			.andExpect(jsonPath("$.settlement").value("REFUND"))
			.andExpect(jsonPath("$.refundMethod").value("CASH"))
			.andExpect(jsonPath("$.totalAmount").value(41500))
			.andExpect(jsonPath("$.reason").value("Ne convient pas"))
			.andExpect(jsonPath("$.createdByUserId").value(this.manager.toString()))
			.andExpect(jsonPath("$.lines[0].amount").value(31500))
			.andExpect(jsonPath("$.lines[0].stockMovementId").isString())
			.andExpect(jsonPath("$.lines[1].amount").value(10000))
			.andExpect(jsonPath("$.lines[1].condition").value("DEFECTIVE"))
			.andExpect(jsonPath("$.lines[1].stockMovementId").doesNotExist()));

		assertThat(level(this.frame)).as("monture revendable remise en stock").isEqualTo(8);
		assertThat(level(this.lens)).as("lentille défectueuse non remise en stock").isEqualTo(8);
		this.mvc.perform(get("/api/stock/movements").with(manager()).param("productId", this.frame.toString())
			.param("sort", "createdAt,desc"))
			.andExpect(jsonPath("$.items[0].type").value("CUSTOMER_RETURN"))
			.andExpect(jsonPath("$.items[0].quantity").value(1))
			.andExpect(jsonPath("$.items[0].sourceId").value(first))
			.andExpect(jsonPath("$.items[0].unitCost").value(15000));
		// 50 000 + 114 500 encaissés − 41 500 remboursés.
		this.mvc.perform(get("/api/cash-register/current").with(manager()))
			.andExpect(jsonPath("$.cashSalesTotal").value(73000))
			.andExpect(jsonPath("$.expectedCash").value(123000));
		assertThat(this.jdbc.queryForObject("""
				select count(*) from cash_entry
				where entry_type = 'RETURN_REFUND' and source_type = 'CUSTOMER_RETURN' and source_id = ?::uuid
				  and amount = 41500""", Integer.class, first)).isEqualTo(1);

		// Le reste des montures : la dernière unité reprend le reliquat (63 000, pas d'écart d'arrondi).
		giveBack(sale, """
				{"settlement": "REFUND", "reason": "Lot complet", "items": [
				  {"productId": "%s", "quantity": 2, "condition": "RESELLABLE"}]}""".formatted(this.frame))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.totalAmount").value(63000));
		giveBack(sale, """
				{"settlement": "REFUND", "reason": "Une de trop", "items": [
				  {"productId": "%s", "quantity": 1, "condition": "RESELLABLE"}]}""".formatted(this.frame))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("RETURN_QUANTITY_EXCEEDS_SOLD"))
			.andExpect(jsonPath("$.details.sold").value(3))
			.andExpect(jsonPath("$.details.alreadyReturned").value(3));
		assertThat(level(this.frame)).isEqualTo(10);

		// La vente est conservée telle quelle, ses retours s'y rattachent ; elle ne s'annule plus.
		this.mvc.perform(get("/api/sales/{id}", sale).with(manager()))
			.andExpect(jsonPath("$.status").value("COMPLETED"))
			.andExpect(jsonPath("$.total").value(114500));
		this.mvc.perform(get("/api/sales/{id}/returns", sale).with(manager()))
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].id").value(first));
		this.mvc.perform(post("/api/sales/{id}/cancel", sale).with(manager()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\": \"Tout annuler\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SALE_HAS_RETURNS"));

		assertThat(this.jdbc.queryForObject("""
				select count(*) from audit_log
				where action = 'CREATE' and entity_type = 'CUSTOMER_RETURN' and user_id = ? and status = 'SUCCESS'""",
				Integer.class, this.manager)).isEqualTo(2);
		checkDeferredConstraints();
	}

	@Test
	void theRefundIsCappedByWhatTheCustomerPaid() throws Exception {
		// 70 000 dont 10 000 payés : le reste est une créance.
		String sale = idOf(sell("""
				{"customerId": "%s", "paymentMethod": "MOBILE_MONEY", "paidAmount": 10000,
				 "items": [{"productId": "%s", "quantity": 2}]}""".formatted(this.customer, this.frame)));

		giveBack(sale, """
				{"settlement": "REFUND", "reason": "Rendu", "items": [
				  {"productId": "%s", "quantity": 1, "condition": "RESELLABLE"}]}""".formatted(this.frame))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("RETURN_REFUND_EXCEEDS_PAID"))
			.andExpect(jsonPath("$.details.refundable").value(10000));
		assertThat(level(this.frame)).isEqualTo(8);
		assertThat(returnCount()).isZero();

		// Avoir sur compte : la dette baisse d'autant, sans mouvement de caisse.
		giveBack(sale, """
				{"settlement": "ACCOUNT_CREDIT", "reason": "Rendu", "items": [
				  {"productId": "%s", "quantity": 1, "condition": "RESELLABLE"}]}""".formatted(this.frame))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.settlement").value("ACCOUNT_CREDIT"))
			.andExpect(jsonPath("$.refundMethod").doesNotExist())
			.andExpect(jsonPath("$.customerId").value(this.customer.toString()))
			.andExpect(jsonPath("$.totalAmount").value(35000));
		this.mvc.perform(get("/api/customers/{id}/account", this.customer).with(manager()))
			.andExpect(jsonPath("$.balance").value(25000));
		assertThat(this.jdbc.queryForObject("""
				select count(*) from customer_account_entry
				where entry_type = 'CREDIT_NOTE' and source_type = 'CUSTOMER_RETURN' and customer_id = ?""",
				Integer.class, this.customer)).isEqualTo(1);
		assertThat(level(this.frame)).isEqualTo(9);

		// Le dernier article (35 000) dépasse toujours les 10 000 payés : refusé en remboursement.
		giveBack(sale, """
				{"settlement": "REFUND", "reason": "Second", "items": [
				  {"productId": "%s", "quantity": 1, "condition": "DEFECTIVE"}]}""".formatted(this.frame))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("RETURN_REFUND_EXCEEDS_PAID"));
		checkDeferredConstraints();
	}

	@Test
	void anAccountCreditNeedsACustomer() throws Exception {
		String sale = idOf(sell("""
				{"paymentMethod": "CASH", "items": [{"productId": "%s", "quantity": 1}]}""".formatted(this.lens)));

		giveBack(sale, """
				{"settlement": "ACCOUNT_CREDIT", "reason": "Avoir", "items": [
				  {"productId": "%s", "quantity": 1, "condition": "RESELLABLE"}]}""".formatted(this.lens))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("RETURN_CUSTOMER_REQUIRED"));
		giveBack(sale, """
				{"settlement": "REFUND", "refundMethod": "MOBILE_MONEY", "reason": "Remboursé par Orange Money",
				 "items": [{"productId": "%s", "quantity": 1, "condition": "RESELLABLE"}]}""".formatted(this.lens))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.refundMethod").value("MOBILE_MONEY"));
		// Remboursement hors espèces : les espèces attendues ne bougent pas.
		this.mvc.perform(get("/api/cash-register/current").with(manager()))
			.andExpect(jsonPath("$.expectedCash").value(60000));
		checkDeferredConstraints();
	}

	@Test
	void aCancelledSaleCannotBeReturnedAndARefundNeedsAnOpenDrawer() throws Exception {
		String sale = idOf(sell("""
				{"paymentMethod": "CASH", "items": [{"productId": "%s", "quantity": 1}]}""".formatted(this.frame)));
		String body = """
				{"settlement": "REFUND", "reason": "Rendu", "items": [
				  {"productId": "%s", "quantity": 1, "condition": "RESELLABLE"}]}""".formatted(this.frame);

		// Un autre vendeur, sans caisse ouverte, ne peut pas rembourser.
		UUID other = this.jdbc.queryForObject(
				"insert into app_user (username, last_name) values ('return_api_other', 'Autre') returning id", UUID.class);
		this.mvc.perform(post("/api/sales/{id}/returns", sale)
			.with(authentication(StockFixtures.authentication(other, Permissions.ALL.toArray(String[]::new))))
			.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("NO_OPEN_CASH_SESSION"));
		assertThat(returnCount()).isZero();

		this.mvc.perform(post("/api/sales/{id}/cancel", sale).with(manager()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\": \"Erreur\"}"))
			.andExpect(status().isOk());
		giveBack(sale, body)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SALE_NOT_RETURNABLE"));
		giveBack(UUID.randomUUID().toString(), body)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("SALE_NOT_FOUND"));
		assertThat(level(this.frame)).isEqualTo(10);
		checkDeferredConstraints();
	}

	// --- Validation, consultation, droits -------------------------------------------------------------

	@Test
	void requestsAreValidated() throws Exception {
		String sale = idOf(sell("""
				{"paymentMethod": "CASH", "items": [{"productId": "%s", "quantity": 2}]}""".formatted(this.frame)));

		giveBack(sale, """
				{"settlement": "REFUND", "items": [{"productId": "%s", "quantity": 1, "condition": "RESELLABLE"}]}"""
			.formatted(this.frame))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		giveBack(sale, "{\"settlement\": \"REFUND\", \"reason\": \"x\", \"items\": []}")
			.andExpect(status().isBadRequest());
		giveBack(sale, """
				{"settlement": "REFUND", "reason": "x", "items": [{"productId": "%s", "quantity": 0, "condition": "RESELLABLE"}]}"""
			.formatted(this.frame))
			.andExpect(status().isBadRequest());
		giveBack(sale, """
				{"settlement": "REFUND", "reason": "x", "items": [{"productId": "%s", "quantity": 1}]}"""
			.formatted(this.frame))
			.andExpect(status().isBadRequest());
		giveBack(sale, """
				{"settlement": "REFUND", "reason": "x", "items": [
				  {"productId": "%s", "quantity": 1, "condition": "RESELLABLE"},
				  {"productId": "%s", "quantity": 1, "condition": "DEFECTIVE"}]}""".formatted(this.frame, this.frame))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("RETURN_DUPLICATE_PRODUCT"));
		giveBack(sale, """
				{"settlement": "REFUND", "reason": "x", "items": [{"productId": "%s", "quantity": 1, "condition": "RESELLABLE"}]}"""
			.formatted(this.lens))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("RETURN_PRODUCT_NOT_SOLD"));
		assertThat(returnCount()).isZero();
	}

	@Test
	void returnsAreSearchableAndOnlyAuthorisedUsersCreateThem() throws Exception {
		String cashSale = idOf(sell("""
				{"paymentMethod": "CASH", "items": [{"productId": "%s", "quantity": 1}]}""".formatted(this.frame)));
		String creditSale = idOf(sell("""
				{"customerId": "%s", "paidAmount": 0, "items": [{"productId": "%s", "quantity": 1}]}"""
			.formatted(this.customer, this.lens)));
		String body = """
				{"settlement": "%s", "reason": "Rendu", "items": [
				  {"productId": "%s", "quantity": 1, "condition": "RESELLABLE"}]}""";

		// Un caissier (sans return:create) consulte mais n'enregistre pas de retour.
		RequestPostProcessor cashier = actor(Permissions.SALE_READ, Permissions.SALE_CREATE, Permissions.CASH_OPERATE,
				Permissions.WAREHOUSE_READ);
		this.mvc.perform(post("/api/sales/{id}/returns", cashSale).with(cashier).contentType(MediaType.APPLICATION_JSON)
			.content(body.formatted("REFUND", this.frame)))
			.andExpect(status().isForbidden());

		String refund = idOf(giveBack(cashSale, body.formatted("REFUND", this.frame)));
		idOf(giveBack(creditSale, body.formatted("ACCOUNT_CREDIT", this.lens)));

		this.mvc.perform(get("/api/returns").with(cashier))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(2));
		this.mvc.perform(get("/api/returns").with(manager()).param("settlement", "REFUND"))
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].id").value(refund))
			.andExpect(jsonPath("$.items[0].lines[0].productSku").value("RET-MON-1"));
		this.mvc.perform(get("/api/returns").with(manager()).param("customerId", this.customer.toString()))
			.andExpect(jsonPath("$.total").value(1));
		this.mvc.perform(get("/api/returns").with(manager()).param("from", "2000-01-01T00:00:00Z")
			.param("to", "2001-01-01T00:00:00Z"))
			.andExpect(jsonPath("$.total").value(0));
		this.mvc.perform(get("/api/returns").with(manager()).param("page", "0").param("size", "1"))
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.totalPages").value(2));
		this.mvc.perform(get("/api/returns/{id}", refund).with(cashier))
			.andExpect(jsonPath("$.lines.length()").value(1));
		this.mvc.perform(get("/api/returns/{id}", UUID.randomUUID()).with(manager()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RETURN_NOT_FOUND"));
		this.mvc.perform(get("/api/returns").with(actor(Permissions.STOCK_READ))).andExpect(status().isForbidden());
		checkDeferredConstraints();
	}

	// --- Outils ------------------------------------------------------------------------------------

	/** Déclenche les contraintes différées (V017, V020) avant le rollback du test. */
	private void checkDeferredConstraints() {
		this.jdbc.execute("set constraints all immediate");
	}

	private UUID product(String sku, String name, int purchasePrice, int retailPrice) {
		return this.jdbc.queryForObject("""
				insert into product (sku, name, category_id, purchase_price, retail_price, wholesale_price)
				values (?, ?, (select id from category where name = 'Montures'), ?, ?, ?) returning id""", UUID.class,
				sku, name, purchasePrice, retailPrice, retailPrice);
	}

	private void stockIn(UUID productId, int quantity) throws Exception {
		this.mvc.perform(post("/api/stock/movements").with(manager()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"productId\": \"%s\", \"quantity\": %d, \"reason\": \"SUPPLY\"}".formatted(productId, quantity)))
			.andExpect(status().isCreated());
	}

	private ResultActions sell(String body) throws Exception {
		return this.mvc.perform(post("/api/sales").with(manager()).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions giveBack(String saleId, String body) throws Exception {
		return this.mvc.perform(post("/api/sales/{id}/returns", saleId).with(manager())
			.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private int level(UUID productId) {
		return this.jdbc.queryForList("select quantity from stock_level where warehouse_id = ? and product_id = ?",
				Integer.class, this.main, productId).stream().findFirst().orElse(0);
	}

	private int returnCount() {
		return this.jdbc.queryForObject("select count(*) from customer_return", Integer.class);
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
