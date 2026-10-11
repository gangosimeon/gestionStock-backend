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
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Paiements fournisseurs (V025) de bout en bout : imputation sur une commande ou sur les plus
 * anciennes, plafond du reste dû, caisse pour les espèces, compte fournisseur, verrouillage des
 * annulations, droits. Les contraintes différées sont forcées avant le rollback de chaque test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@Transactional
class SupplierPaymentApiIT {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private JsonMapper json;

	private UUID manager;

	private UUID supplier;

	private UUID otherSupplier;

	private UUID frame;

	@BeforeEach
	void setUp() {
		this.manager = this.jdbc.queryForObject(
				"insert into app_user (username, last_name) values ('supplier_pay_mgr', 'Comptable') returning id",
				UUID.class);
		this.supplier = this.jdbc.queryForObject("""
				insert into supplier (code, name, delivery_lead_time_days, payment_terms_days)
				values ('FOU-PAY', 'Essilor Paiements', 5, 30) returning id""", UUID.class);
		this.otherSupplier = this.jdbc.queryForObject("""
				insert into supplier (code, name, delivery_lead_time_days, payment_terms_days)
				values ('FOU-PAY2', 'Autre fournisseur', 5, 30) returning id""", UUID.class);
		this.frame = this.jdbc.queryForObject("""
				insert into product (sku, name, category_id, purchase_price, retail_price, wholesale_price)
				values ('PAY-MON-1', 'Monture paiement', (select id from category where name = 'Montures'), 10000, 20000, 18000)
				returning id""", UUID.class);
	}

	@Test
	void aPaymentOnAnOrderIsCappedByItsRemainingAndUpdatesItsPaidAmount() throws Exception {
		String order = createOrder(this.supplier, 10); // 100 000
		pay(this.supplier, """
				{"amount": 40000, "paymentDateIso": "%s", "paymentMethod": "BANK_TRANSFER", "orderId": "%s",
				 "externalReference": "VIR-1", "note": "Acompte"}""".formatted(LocalDate.now(ZoneOffset.UTC), order))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.number").value(matchesPattern("PAF-\\d{4}-\\d{6}")))
			.andExpect(jsonPath("$.supplierId").value(this.supplier.toString()))
			.andExpect(jsonPath("$.amount").value(40000))
			.andExpect(jsonPath("$.paymentMethod").value("BANK_TRANSFER"))
			.andExpect(jsonPath("$.orderId").value(order))
			.andExpect(jsonPath("$.allocations[0].orderNumber").value(matchesPattern("CMD-\\d{4}-\\d{6}")))
			.andExpect(jsonPath("$.allocations[0].amount").value(40000))
			.andExpect(jsonPath("$.externalReference").value("VIR-1"))
			.andExpect(jsonPath("$.createdByUserId").value(this.manager.toString()));

		this.mvc.perform(get("/api/purchases/orders/{id}", order).with(manager()))
			.andExpect(jsonPath("$.paidAmount").value(40000));
		// Reste à payer 60 000 : 60 000,01 est refusé, 60 000 accepté.
		pay(this.supplier, payment("60000.01", "BANK_TRANSFER", order))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("PURCHASE_ORDER_PAYMENT_EXCEEDS_REMAINING"));
		pay(this.supplier, payment("60000", "MOBILE_MONEY", order)).andExpect(status().isCreated());
		pay(this.supplier, payment("1", "BANK_TRANSFER", order))
			.andExpect(status().isUnprocessableContent());
		checkDeferredConstraints();
	}

	@Test
	void aGlobalPaymentSettlesTheOldestOrdersFirstAndNeverExceedsTheBalance() throws Exception {
		String first = createOrder(this.supplier, 3); // 30 000
		String second = createOrder(this.supplier, 5); // 50 000
		createOrder(this.otherSupplier, 50);

		pay(this.supplier, payment("45000", "BANK_TRANSFER", null))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.orderId").doesNotExist())
			.andExpect(jsonPath("$.allocations.length()").value(2))
			.andExpect(jsonPath("$.allocations[0].orderId").value(first))
			.andExpect(jsonPath("$.allocations[0].amount").value(30000))
			.andExpect(jsonPath("$.allocations[1].orderId").value(second))
			.andExpect(jsonPath("$.allocations[1].amount").value(15000));

		// Reste dû au fournisseur : 35 000 (les commandes de l'autre fournisseur ne comptent pas).
		pay(this.supplier, payment("35000.01", "BANK_TRANSFER", null))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("SUPPLIER_PAYMENT_EXCEEDS_BALANCE"))
			.andExpect(jsonPath("$.details.remainingToPay").value(35000));

		this.mvc.perform(get("/api/suppliers/{id}/account", this.supplier).with(manager()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.orderedAmount").value(80000))
			.andExpect(jsonPath("$.receivedAmount").value(0))
			.andExpect(jsonPath("$.payableAmount").value(80000))
			.andExpect(jsonPath("$.paidAmount").value(45000))
			.andExpect(jsonPath("$.remainingToPay").value(35000))
			.andExpect(jsonPath("$.advanceAmount").value(45000))
			.andExpect(jsonPath("$.ordersToPay").value(1));

		this.mvc.perform(get("/api/suppliers/{id}/payments", this.supplier).with(manager()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].allocations.length()").value(2));
		assertThat(this.jdbc.queryForObject(
				"select sum(paid_amount) from purchase_order where supplier_id = ?", Integer.class, this.supplier))
			.isEqualTo(45000);
		checkDeferredConstraints();
	}

	@Test
	void requestsAreValidatedServerSide() throws Exception {
		String order = createOrder(this.supplier, 1);
		String foreign = createOrder(this.otherSupplier, 1);
		pay(this.supplier, payment("0", "BANK_TRANSFER", order)).andExpect(status().isBadRequest());
		pay(this.supplier, payment("10.005", "BANK_TRANSFER", order)).andExpect(status().isBadRequest());
		pay(this.supplier, """
				{"amount": 100, "paymentMethod": "BANK_TRANSFER", "paymentDate": "%s"}"""
			.formatted(LocalDate.now(ZoneOffset.UTC).plusDays(2)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("SUPPLIER_PAYMENT_DATE_IN_FUTURE"));
		pay(this.supplier, payment("100", "BANK_TRANSFER", foreign))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("SUPPLIER_PAYMENT_ORDER_MISMATCH"));
		pay(UUID.randomUUID(), payment("100", "BANK_TRANSFER", null)).andExpect(status().isNotFound());
		assertThat(this.jdbc.queryForObject("select count(*) from supplier_payment where supplier_id = ?",
				Integer.class, this.supplier)).isZero();
	}

	@Test
	void aCashPaymentLeavesMyOpenCashRegisterAndOnlyIfTheCashIsThere() throws Exception {
		String order = createOrder(this.supplier, 5); // 50 000
		// Pas de caisse ouverte : refusé, rien n'est enregistré.
		pay(this.supplier, payment("20000", "CASH", order)).andExpect(status().isConflict());

		this.mvc.perform(post("/api/cash-register/open").with(manager()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"openingBalance\": 25000}")).andExpect(status().isCreated());
		pay(this.supplier, payment("30000", "CASH", order))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("CASH_INSUFFICIENT"));
		String payment = idOf(pay(this.supplier, payment("20000", "CASH", order)));

		assertThat(this.jdbc.queryForObject("""
				select count(*) from cash_entry where entry_type = 'SUPPLIER_PAYMENT' and direction = 'OUT'
				and source_type = 'SUPPLIER_PAYMENT' and source_id = ?::uuid and amount = 20000""", Integer.class, payment))
			.isOne();
		this.mvc.perform(get("/api/cash-register/current").with(manager()))
			.andExpect(jsonPath("$.totalOut").value(20000))
			.andExpect(jsonPath("$.expectedCash").value(5000));
		// Le défaut est l'espèce, comme pour les règlements clients.
		pay(this.supplier, "{\"amount\": 5000, \"orderId\": \"%s\"}".formatted(order))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.paymentMethod").value("CASH"));
		checkDeferredConstraints();
	}

	@Test
	void theOrderRouteUsesTheSameRuleAndReturnsTheUpdatedOrder() throws Exception {
		String order = createOrder(this.supplier, 2); // 20 000
		this.mvc.perform(post("/api/purchases/orders/{id}/pay", order).with(manager())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"amount\": 20000, \"paymentMethod\": \"BANK_TRANSFER\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(order))
			.andExpect(jsonPath("$.paidAmount").value(20000));
		this.mvc.perform(post("/api/purchases/orders/{id}/pay", order).with(manager())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"amount\": 1, \"paymentMethod\": \"BANK_TRANSFER\"}"))
			.andExpect(status().isUnprocessableContent());
		checkDeferredConstraints();
	}

	@Test
	void paidOrdersCanNeitherBeCancelledNorClosedBelowWhatWasPaid() throws Exception {
		String prepaid = createOrder(this.supplier, 4); // 40 000, rien reçu
		pay(this.supplier, payment("1000", "BANK_TRANSFER", prepaid)).andExpect(status().isCreated());
		this.mvc.perform(post("/api/purchases/orders/{id}/cancel", prepaid).with(manager()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PURCHASE_ORDER_PREPAID"));

		String partial = createOrder(this.supplier, 10); // 100 000
		receive(partial, "{\"lines\": [{\"productId\": \"%s\", \"quantity\": 3}]}".formatted(this.frame)); // 30 000 reçus
		pay(this.supplier, payment("50000", "BANK_TRANSFER", partial)).andExpect(status().isCreated());
		this.mvc.perform(post("/api/purchases/orders/{id}/close", partial).with(manager())
			.contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"Reliquat abandonné\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PURCHASE_ORDER_PREPAID"));

		// Soldée sans trop-payé : seule la valeur reçue reste due.
		String closable = createOrder(this.supplier, 10);
		receive(closable, "{\"lines\": [{\"productId\": \"%s\", \"quantity\": 2}]}".formatted(this.frame)); // 20 000
		pay(this.supplier, payment("5000", "BANK_TRANSFER", closable)).andExpect(status().isCreated());
		this.mvc.perform(post("/api/purchases/orders/{id}/close", closable).with(manager())
			.contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"Fin\"}")).andExpect(status().isOk());
		pay(this.supplier, payment("15000.01", "BANK_TRANSFER", closable))
			.andExpect(status().isUnprocessableContent());
		pay(this.supplier, payment("15000", "BANK_TRANSFER", closable)).andExpect(status().isCreated());
		checkDeferredConstraints();
	}

	@Test
	void onlyBuyersAllowedToPayCanPay() throws Exception {
		String order = createOrder(this.supplier, 1);
		RequestPostProcessor reader = actor(Permissions.PURCHASE_READ);
		this.mvc.perform(get("/api/suppliers/{id}/payments", this.supplier).with(reader)).andExpect(status().isOk());
		this.mvc.perform(get("/api/suppliers/{id}/account", this.supplier).with(reader)).andExpect(status().isOk());
		this.mvc.perform(post("/api/suppliers/{id}/payments", this.supplier).with(reader)
			.contentType(MediaType.APPLICATION_JSON).content(payment("100", "BANK_TRANSFER", order)))
			.andExpect(status().isForbidden());
		this.mvc.perform(post("/api/purchases/orders/{id}/pay", order).with(reader)
			.contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 100}"))
			.andExpect(status().isForbidden());
		RequestPostProcessor cashier = actor(Permissions.SALE_CREATE, Permissions.SUPPLIER_READ);
		this.mvc.perform(get("/api/suppliers/{id}/payments", this.supplier).with(cashier))
			.andExpect(status().isForbidden());
		this.mvc.perform(get("/api/suppliers/{id}/account", this.supplier).with(cashier))
			.andExpect(status().isForbidden());
	}

	@Test
	void everyPaymentIsAudited() throws Exception {
		String order = createOrder(this.supplier, 1);
		String payment = idOf(pay(this.supplier, payment("100", "BANK_TRANSFER", order)));
		assertThat(this.jdbc.queryForObject("""
				select count(*) from audit_log where action = 'PAY' and entity_type = 'SUPPLIER_PAYMENT'
				and entity_id = ? and module = 'PURCHASE' and status = 'SUCCESS' and user_id = ?""", Integer.class,
				payment, this.manager)).isOne();
	}

	// --- Outils ------------------------------------------------------------------------------------

	private void checkDeferredConstraints() {
		this.jdbc.execute("set constraints all immediate");
	}

	/** Commande de {@code quantity} montures à 10 000 dans le dépôt principal. */
	private String createOrder(UUID supplierId, int quantity) throws Exception {
		return idOf(this.mvc.perform(post("/api/purchases/orders").with(manager())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"supplierId": "%s", "lines": [{"productId": "%s", "quantity": %d, "unitPurchasePrice": 10000}]}"""
				.formatted(supplierId, this.frame, quantity))));
	}

	private void receive(String orderId, String body) throws Exception {
		this.mvc.perform(post("/api/purchases/orders/{id}/receive", orderId).with(manager())
			.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
	}

	private String payment(String amount, String method, String orderId) {
		return "{\"amount\": %s, \"paymentMethod\": \"%s\"%s}".formatted(amount, method,
				orderId != null ? ", \"orderId\": \"" + orderId + "\"" : "");
	}

	private ResultActions pay(UUID supplierId, String body) throws Exception {
		return this.mvc.perform(post("/api/suppliers/{id}/payments", supplierId).with(manager())
			.contentType(MediaType.APPLICATION_JSON).content(body));
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
