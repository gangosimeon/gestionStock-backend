package com.geststock.cash;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Caisse de bout en bout sur PostgreSQL : contrat front, caisses individuelles, contrôles des
 * sorties, clôture et écart, encaissement des ventes, règlements clients, droits. Chaque test
 * s'exécute dans une transaction annulée ; les contraintes différées sont forcées avant la fin.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@Transactional
class CashApiIT {

	private static final String[] CASHIER = { Permissions.CASH_OPERATE, Permissions.SALE_CREATE, Permissions.SALE_READ,
			Permissions.PRODUCT_READ, Permissions.CUSTOMER_READ, Permissions.CUSTOMER_PAY, Permissions.WAREHOUSE_READ,
			Permissions.STOCK_READ };

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private JsonMapper json;

	private UUID alice;

	private UUID bruno;

	private UUID frame;

	private UUID customer;

	@BeforeEach
	void setUp() throws Exception {
		this.alice = user("cash_alice", "Alice");
		this.bruno = user("cash_bruno", "Bruno");
		this.frame = this.jdbc.queryForObject("""
				insert into product (sku, name, category_id, purchase_price, retail_price, wholesale_price)
				values ('CAI-MON-1', 'Monture caisse', (select id from category where name = 'Montures'), 15000, 35000, 28000)
				returning id""", UUID.class);
		this.customer = this.jdbc.queryForObject("""
				insert into customer (code, name, credit_limit) values ('CLI-CAI-API', 'Client caisse', 100000) returning id""",
				UUID.class);
		this.mvc.perform(post("/api/stock/movements").with(admin(this.alice)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"productId\": \"%s\", \"quantity\": 10, \"reason\": \"SUPPLY\"}".formatted(this.frame)))
			.andExpect(status().isCreated());
	}

	@Test
	void openOperateAndCloseWithTheFrontContract() throws Exception {
		this.mvc.perform(get("/api/cash-register/current").with(cashier(this.alice)))
			.andExpect(status().isOk())
			.andExpect(content().string("null"));

		String session = idOf(open(this.alice, "{\"openingBalance\": 20000}")
			.andExpect(jsonPath("$.number").value(matchesPattern("CAI-\\d{4}-\\d{6}")))
			.andExpect(jsonPath("$.status").value("OPEN"))
			.andExpect(jsonPath("$.openedByUserId").value(this.alice.toString()))
			.andExpect(jsonPath("$.openingBalance").value(20000))
			.andExpect(jsonPath("$.expectedCash").value(20000))
			.andExpect(jsonPath("$.difference").value(0))
			.andExpect(jsonPath("$.openedAt").isString()));
		this.mvc.perform(get("/api/cash-register/current").with(cashier(this.alice)))
			.andExpect(jsonPath("$.id").value(session));
		open(this.alice, "{\"openingBalance\": 0}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CASH_SESSION_ALREADY_OPEN"));

		// Apport puis sorties : une sortie ne peut dépasser les espèces attendues.
		operation(this.alice, "{\"type\": \"IN\", \"amount\": 5000, \"note\": \"Appoint monnaie\"}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.type").value("IN"))
			.andExpect(jsonPath("$.entryType").value("CASH_IN"))
			.andExpect(jsonPath("$.number").value(matchesPattern("OPC-\\d{4}-\\d{6}")))
			.andExpect(jsonPath("$.sessionId").value(session))
			.andExpect(jsonPath("$.paymentMethod").value("CASH"))
			.andExpect(jsonPath("$.createdByUserId").value(this.alice.toString()))
			.andExpect(jsonPath("$.createdAt").isString());
		operation(this.alice, "{\"type\": \"OUT\", \"amount\": 30000}")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("CASH_INSUFFICIENT"))
			.andExpect(jsonPath("$.details.available").value(25000));
		operation(this.alice, "{\"type\": \"OUT\", \"amount\": 10000, \"note\": \"Dépôt en banque\"}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.entryType").value("CASH_OUT"));

		this.mvc.perform(get("/api/cash-register/sessions/{id}/operations", session).with(cashier(this.alice)))
			.andExpect(jsonPath("$.total").value(2));
		this.mvc.perform(get("/api/cash-register/current").with(cashier(this.alice)))
			.andExpect(jsonPath("$.totalIn").value(5000))
			.andExpect(jsonPath("$.totalOut").value(10000))
			.andExpect(jsonPath("$.expectedCash").value(15000));

		// Clôture : 14 000 comptés pour 15 000 attendus.
		close(this.alice, "{\"countedCash\": 14000, \"note\": \"Pièce manquante\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CLOSED"))
			.andExpect(jsonPath("$.countedCash").value(14000))
			.andExpect(jsonPath("$.expectedCash").value(15000))
			.andExpect(jsonPath("$.difference").value(-1000))
			.andExpect(jsonPath("$.closedByUserId").value(this.alice.toString()))
			.andExpect(jsonPath("$.closingNote").value("Pièce manquante"));
		this.mvc.perform(get("/api/cash-register/current").with(cashier(this.alice)))
			.andExpect(content().string("null"));
		operation(this.alice, "{\"type\": \"IN\", \"amount\": 1}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("NO_OPEN_CASH_SESSION"));
		close(this.alice, "{\"countedCash\": 0}").andExpect(status().isConflict());

		assertThat(this.jdbc.queryForList("""
				select action from audit_log where user_id = ? and entity_type in ('CASH_SESSION', 'CASH_ENTRY') and status = 'SUCCESS'
				order by created_at""", String.class, this.alice))
			.containsExactlyInAnyOrder("OPEN", "CASH_IN", "CASH_OUT", "CLOSE");
		checkDeferredConstraints();
	}

	@Test
	void eachCashierHasHisOwnDrawer() throws Exception {
		String aliceSession = idOf(open(this.alice, "{\"openingBalance\": 10000, \"terminal\": \"Caisse 1\"}")
			.andExpect(jsonPath("$.terminal").value("Caisse 1")));
		String brunoSession = idOf(open(this.bruno, "{\"openingBalance\": 5000, \"terminal\": \"Caisse 2\"}"));
		assertThat(brunoSession).isNotEqualTo(aliceSession);

		operation(this.bruno, "{\"type\": \"IN\", \"amount\": 2000}")
			.andExpect(jsonPath("$.sessionId").value(brunoSession))
			.andExpect(jsonPath("$.createdByUserId").value(this.bruno.toString()));
		this.mvc.perform(get("/api/cash-register/current").with(cashier(this.alice)))
			.andExpect(jsonPath("$.expectedCash").value(10000));

		// Chacun ne voit que sa caisse ; le superviseur (cash:read) voit les deux.
		this.mvc.perform(get("/api/cash-register/sessions").with(cashier(this.alice)))
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].id").value(aliceSession));
		this.mvc.perform(get("/api/cash-register/sessions/{id}", brunoSession).with(cashier(this.alice)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("CASH_SESSION_NOT_FOUND"));
		RequestPostProcessor supervisor = actor(this.alice, Permissions.CASH_READ, Permissions.WAREHOUSE_READ);
		this.mvc.perform(get("/api/cash-register/sessions").with(supervisor))
			.andExpect(jsonPath("$.total").value(2));
		this.mvc.perform(get("/api/cash-register/sessions").with(supervisor).param("cashierId", this.bruno.toString()))
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].openedByUserId").value(this.bruno.toString()));
		this.mvc.perform(get("/api/cash-register/sessions/{id}/operations", brunoSession).with(supervisor))
			.andExpect(jsonPath("$.items[*].createdByUserId", everyItem(org.hamcrest.Matchers.is(this.bruno.toString()))));
	}

	@Test
	void salesAreEncashedInTheSellersOwnDrawer() throws Exception {
		sell(this.alice, "{\"paymentMethod\": \"CASH\", \"items\": [{\"productId\": \"%s\", \"quantity\": 1}]}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("NO_OPEN_CASH_SESSION"));
		assertThat(this.jdbc.queryForObject("select count(*) from sale", Integer.class)).isZero();

		String session = idOf(open(this.alice, "{\"openingBalance\": 10000}"));
		String cashSale = idOf(sell(this.alice,
				"{\"paymentMethod\": \"CASH\", \"items\": [{\"productId\": \"%s\", \"quantity\": 1}]}"));
		idOf(sell(this.alice, "{\"paymentMethod\": \"MOBILE_MONEY\", \"items\": [{\"productId\": \"%s\", \"quantity\": 2}]}"));

		this.mvc.perform(get("/api/cash-register/current").with(cashier(this.alice)))
			.andExpect(jsonPath("$.cashSalesTotal").value(35000))
			.andExpect(jsonPath("$.expectedCash").value(45000))
			.andExpect(jsonPath("$.totalsByMethod.CASH").value(35000))
			.andExpect(jsonPath("$.totalsByMethod.MOBILE_MONEY").value(70000));
		String number = this.jdbc.queryForObject("select number from sale where id = ?::uuid", String.class, cashSale);
		this.mvc.perform(get("/api/cash-register/sessions/{id}/operations", session).with(cashier(this.alice)))
			.andExpect(jsonPath("$.total").value(2))
			.andExpect(jsonPath("$.items[*].entryType", everyItem(org.hamcrest.Matchers.is("SALE"))))
			.andExpect(jsonPath("$.items[*].sourceReference", hasItem(number)));

		// Bruno n'a pas de caisse : il ne peut pas encaisser, même si Alice en a une.
		sell(this.bruno, "{\"paymentMethod\": \"CASH\", \"items\": [{\"productId\": \"%s\", \"quantity\": 1}]}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("NO_OPEN_CASH_SESSION"));
		checkDeferredConstraints();
	}

	@Test
	void customerPaymentsAreEncashedAndCannotExceedTheDebt() throws Exception {
		// Vente entièrement à crédit : rien à encaisser, pas de caisse requise.
		sell(this.alice, """
				{"customerId": "%s", "paidAmount": 0, "items": [{"productId": "%s", "quantity": 1}]}
				""".formatted(this.customer, this.frame))
			.andExpect(status().isCreated());

		pay(this.alice, "{\"amount\": 20000}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("NO_OPEN_CASH_SESSION"));
		open(this.alice, "{\"openingBalance\": 0}");
		pay(this.alice, "{\"amount\": 50000}")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("AMOUNT_EXCEEDS_CUSTOMER_BALANCE"));

		// Charge utile du front (paymentDateIso ignoré : la date est celle de l'encaissement).
		pay(this.alice, "{\"amount\": 20000, \"paymentDateIso\": \"2020-01-01\", \"note\": \"Acompte\"}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.customerId").value(this.customer.toString()))
			.andExpect(jsonPath("$.amount").value(20000))
			.andExpect(jsonPath("$.paymentMethod").value("CASH"))
			.andExpect(jsonPath("$.reference").value(matchesPattern("OPC-\\d{4}-\\d{6}")))
			.andExpect(jsonPath("$.paymentDateIso").value(LocalDate.now(ZoneOffset.UTC).toString()))
			.andExpect(jsonPath("$.note").value("Acompte"));

		this.mvc.perform(get("/api/customers/{id}/account", this.customer).with(admin(this.alice)))
			.andExpect(jsonPath("$.balance").value(15000));
		this.mvc.perform(get("/api/customers/{id}/payments", this.customer).with(cashier(this.alice)))
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].amount").value(20000));
		this.mvc.perform(get("/api/cash-register/current").with(cashier(this.alice)))
			.andExpect(jsonPath("$.totalIn").value(20000))
			.andExpect(jsonPath("$.expectedCash").value(20000));
		checkDeferredConstraints();
	}

	@Test
	void aManualOperationIsCorrectedByAReversalNeverByDeletion() throws Exception {
		open(this.alice, "{\"openingBalance\": 10000}").andExpect(status().isCreated());
		String cashIn = idOf(operation(this.alice, "{\"type\": \"IN\", \"amount\": 5000, \"note\": \"Saisie en double\"}"));
		String cashOut = idOf(operation(this.alice, "{\"type\": \"OUT\", \"amount\": 3000}"));
		String number = this.jdbc.queryForObject("select number from cash_entry where id = ?::uuid", String.class, cashIn);

		reverse(this.alice, cashIn, "{}").andExpect(status().isBadRequest());
		reverse(this.alice, cashIn, "{\"reason\": \"Apport saisi deux fois\"}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.type").value("OUT"))
			.andExpect(jsonPath("$.entryType").value("CORRECTION_OUT"))
			.andExpect(jsonPath("$.amount").value(5000))
			.andExpect(jsonPath("$.reversalOfId").value(cashIn))
			.andExpect(jsonPath("$.note").value(startsWith("Correction de " + number)));
		reverse(this.alice, cashOut, "{\"reason\": \"Sortie annulée\"}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.entryType").value("CORRECTION_IN"));

		// L'opération d'origine reste dans l'historique ; les totaux sont nets des corrections.
		this.mvc.perform(get("/api/cash-register/current").with(cashier(this.alice)))
			.andExpect(jsonPath("$.totalIn").value(0))
			.andExpect(jsonPath("$.totalOut").value(0))
			.andExpect(jsonPath("$.expectedCash").value(10000));
		assertThat(this.jdbc.queryForObject("select count(*) from cash_entry where id = ?::uuid", Integer.class, cashIn))
			.isEqualTo(1);
		reverse(this.alice, cashIn, "{\"reason\": \"Encore\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CASH_ENTRY_ALREADY_REVERSED"));

		// Une correction ne se corrige pas, une vente non plus (passer par annulation ou retour).
		String correction = this.jdbc.queryForObject("select id::text from cash_entry where reversal_of_id = ?::uuid",
				String.class, cashIn);
		reverse(this.alice, correction, "{\"reason\": \"x\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CASH_ENTRY_NOT_REVERSIBLE"));
		String sale = idOf(sell(this.alice, """
				{"paymentMethod": "CASH", "items": [{"productId": "%s", "quantity": 1}]}"""));
		String saleEntry = this.jdbc.queryForObject("select id::text from cash_entry where source_id = ?::uuid",
				String.class, sale);
		reverse(this.alice, saleEntry, "{\"reason\": \"x\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CASH_ENTRY_NOT_REVERSIBLE"));

		// Seul le titulaire de la caisse ouverte corrige ses opérations.
		open(this.bruno, "{\"openingBalance\": 0}").andExpect(status().isCreated());
		reverse(this.bruno, cashOut, "{\"reason\": \"x\"}")
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("CASH_ENTRY_NOT_FOUND"));
		this.mvc.perform(post("/api/cash-register/operations/{id}/reversal", cashOut)
			.with(actor(this.alice, Permissions.CASH_READ)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\": \"x\"}"))
			.andExpect(status().isForbidden());

		assertThat(this.jdbc.queryForObject(
				"select count(*) from audit_log where action = 'CORRECTION' and entity_type = 'CASH_ENTRY' and user_id = ? and status = 'SUCCESS'",
				Integer.class, this.alice)).isEqualTo(2);
		checkDeferredConstraints();
	}

	@Test
	void theDatabaseOnlyAcceptsExactInverseCorrections() throws Exception {
		open(this.alice, "{\"openingBalance\": 10000}").andExpect(status().isCreated());
		String cashIn = idOf(operation(this.alice, "{\"type\": \"IN\", \"amount\": 5000}"));

		// Même sens que l'original : refusé par la base.
		assertThatThrownBy(() -> this.jdbc.update("""
				insert into cash_entry (number, session_id, entry_type, direction, payment_method, amount, reversal_of_id,
				                        created_at, created_by)
				select 'OPC-IT-FAUX', session_id, 'CORRECTION_IN', 'IN', 'CASH', amount, id, now(), created_by
				from cash_entry where id = ?::uuid""", cashIn))
			.hasStackTraceContaining("une correction annule");
	}

	@Test
	void requestsAreValidatedAndPermissionsEnforced() throws Exception {
		open(this.alice, "{\"openingBalance\": -1}").andExpect(status().isBadRequest());
		open(this.alice, "{}").andExpect(status().isBadRequest());
		open(this.alice, "{\"openingBalance\": 10000}").andExpect(status().isCreated());
		operation(this.alice, "{\"type\": \"SIDEWAYS\", \"amount\": 10}").andExpect(status().isBadRequest());
		operation(this.alice, "{\"type\": \"IN\", \"amount\": 0}").andExpect(status().isBadRequest());
		close(this.alice, "{}").andExpect(status().isBadRequest());

		RequestPostProcessor seller = actor(this.bruno, Permissions.SALE_CREATE, Permissions.SALE_READ);
		this.mvc.perform(post("/api/cash-register/open").with(seller).contentType(MediaType.APPLICATION_JSON)
			.content("{\"openingBalance\": 0}"))
			.andExpect(status().isForbidden());
		this.mvc.perform(post("/api/customers/{id}/payments", this.customer).with(actor(this.bruno, Permissions.CUSTOMER_READ))
			.contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 10}"))
			.andExpect(status().isForbidden());
	}

	// --- Outils ------------------------------------------------------------------------------------

	private void checkDeferredConstraints() {
		this.jdbc.execute("set constraints all immediate");
	}

	private UUID user(String username, String name) {
		return this.jdbc.queryForObject("insert into app_user (username, last_name) values (?, ?) returning id",
				UUID.class, username, name);
	}

	private ResultActions open(UUID user, String body) throws Exception {
		return this.mvc.perform(post("/api/cash-register/open").with(cashier(user)).contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private ResultActions operation(UUID user, String body) throws Exception {
		return this.mvc.perform(post("/api/cash-register/operations").with(cashier(user))
			.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions reverse(UUID user, String entryId, String body) throws Exception {
		return this.mvc.perform(post("/api/cash-register/operations/{id}/reversal", entryId).with(cashier(user))
			.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions close(UUID user, String body) throws Exception {
		return this.mvc.perform(post("/api/cash-register/close").with(cashier(user)).contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private ResultActions sell(UUID user, String template) throws Exception {
		String body = template.contains("%s") ? template.formatted(this.frame) : template;
		return this.mvc.perform(post("/api/sales").with(cashier(user)).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions pay(UUID user, String body) throws Exception {
		return this.mvc.perform(post("/api/customers/{id}/payments", this.customer).with(cashier(user))
			.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private RequestPostProcessor cashier(UUID user) {
		return actor(user, CASHIER);
	}

	private RequestPostProcessor admin(UUID user) {
		return actor(user, Permissions.ALL.toArray(String[]::new));
	}

	private RequestPostProcessor actor(UUID user, String... permissions) {
		return authentication(StockFixtures.authentication(user, permissions));
	}

	private String idOf(ResultActions result) throws Exception {
		return this.json.readTree(result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
			.get("id")
			.asString();
	}

}
