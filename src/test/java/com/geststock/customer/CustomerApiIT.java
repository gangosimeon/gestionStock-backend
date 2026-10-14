package com.geststock.customer;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.geststock.common.security.Permissions;
import com.geststock.stock.StockFixtures;
import com.geststock.support.PostgresTestConfiguration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Clients de bout en bout sur PostgreSQL : contrat front, recherche, crédit, droits, compte client. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@Transactional
class CustomerApiIT {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	private UUID user;

	@BeforeEach
	void setUp() {
		this.user = this.jdbc.queryForObject(
				"insert into app_user (username, last_name) values ('customer_api_user', 'Vendeur') returning id",
				UUID.class);
	}

	@Test
	void followsTheFrontContractFromCreationToDeletion() throws Exception {
		// Charge utile exacte du drawer client du front.
		String id = idOf(create(manager(), """
				{"name": "Aminata Traoré", "phone": "+225 05 10 20 30", "email": "aminata.t@gmail.com", "creditLimit": 0}
				""")
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", startsWith("/api/customers/")))
			.andExpect(jsonPath("$.code").value(matchesPattern("CLI-\\d{6,}")))
			.andExpect(jsonPath("$.name").value("Aminata Traoré"))
			.andExpect(jsonPath("$.phone").value("+225 05 10 20 30"))
			.andExpect(jsonPath("$.email").value("aminata.t@gmail.com"))
			.andExpect(jsonPath("$.creditLimit").value(0))
			.andExpect(jsonPath("$.type").value("INDIVIDUAL"))
			.andExpect(jsonPath("$.priceCategory").value("RETAIL"))
			.andExpect(jsonPath("$.balance").value(0))
			.andExpect(jsonPath("$.active").value(true)));

		this.mvc.perform(get("/api/customers").with(manager()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].id", hasItem(id)));

		// Mise à jour du front : PATCH avec la charge complète, email vidé.
		this.mvc.perform(patch("/api/customers/{id}", id).with(manager()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"Aminata Traoré-Koné\", \"phone\": \"+225 05 10 20 31\", \"creditLimit\": 0}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Aminata Traoré-Koné"))
			.andExpect(jsonPath("$.phone").value("+225 05 10 20 31"))
			.andExpect(jsonPath("$.email").doesNotExist());
		this.mvc.perform(put("/api/customers/{id}", id).with(manager()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"Aminata Koné\", \"city\": \"Abidjan\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.city").value("Abidjan"));

		this.mvc.perform(delete("/api/customers/{id}", id).with(manager()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.ok").value(true));
		this.mvc.perform(get("/api/customers").with(manager())).andExpect(jsonPath("$.items[*].id", not(hasItem(id))));
		this.mvc.perform(get("/api/customers").with(manager()).param("status", "ALL"))
			.andExpect(jsonPath("$.items[*].id", hasItem(id)));
		this.mvc.perform(patch("/api/customers/{id}/active", id).with(manager()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"active\": true}"))
			.andExpect(jsonPath("$.active").value(true));
	}

	@Test
	void onlyCreditHoldersCanGrantOrChangeACreditLimit() throws Exception {
		RequestPostProcessor cashier = actor(Permissions.CUSTOMER_READ, Permissions.CUSTOMER_CREATE,
				Permissions.CUSTOMER_UPDATE);

		create(cashier, "{\"name\": \"Client à crédit\", \"creditLimit\": 300000}")
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("CREDIT_LIMIT_CHANGE_FORBIDDEN"));
		String id = idOf(create(cashier, "{\"name\": \"Client comptant\", \"creditLimit\": 0}"));

		edit(cashier, id, "{\"name\": \"Client comptant\", \"creditLimit\": 0}").andExpect(status().isOk());
		edit(cashier, id, "{\"name\": \"Client comptant\", \"creditLimit\": 50000}")
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("CREDIT_LIMIT_CHANGE_FORBIDDEN"));

		edit(manager(), id, "{\"name\": \"Client comptant\", \"creditLimit\": 50000}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.creditLimit").value(50000))
			.andExpect(jsonPath("$.availableCredit").value(50000));
		// Le caissier peut ensuite modifier le client sans toucher au crédit accordé.
		edit(cashier, id, "{\"name\": \"Client fidèle\", \"creditLimit\": 50000}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.creditLimit").value(50000));
	}

	@Test
	void searchesAndPaginatesServerSide() throws Exception {
		create(manager(), """
				{"name": "Zeta Clinique", "type": "COMPANY", "phone": "+225 20 21 00 55", "email": "zeta@clinique.ci",
				 "priceCategory": "RETAIL", "creditLimit": 500000}""");
		create(manager(), """
				{"name": "Zeta Grossiste", "type": "COMPANY", "phone": "+225 20 33 44 55", "priceCategory": "WHOLESALE"}""");
		create(manager(), "{\"name\": \"Zeta Particulier\", \"phone\": \"+225 05 10 99 88\"}");
		create(manager(), "{\"code\": \"ZETA-CODE\", \"name\": \"Autre client\"}");

		this.mvc.perform(get("/api/customers").with(manager()).param("q", "zeta"))
			.andExpect(jsonPath("$.total").value(4));
		this.mvc.perform(get("/api/customers").with(manager()).param("q", "CLINIQUE.CI"))
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].name").value("Zeta Clinique"));
		// Chiffres seuls, sans espaces ni indicatif : trouvé dans « +225 05 10 99 88 ».
		this.mvc.perform(get("/api/customers").with(manager()).param("q", "05109988"))
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].name").value("Zeta Particulier"));
		this.mvc.perform(get("/api/customers").with(manager()).param("q", "zeta").param("type", "COMPANY"))
			.andExpect(jsonPath("$.items[*].name", containsInAnyOrder("Zeta Clinique", "Zeta Grossiste")));
		this.mvc.perform(get("/api/customers").with(manager()).param("q", "zeta").param("priceCategory", "WHOLESALE"))
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].name").value("Zeta Grossiste"));

		this.mvc.perform(get("/api/customers").with(manager()).param("q", "zeta").param("page", "1").param("size", "3")
			.param("sort", "name,desc"))
			.andExpect(jsonPath("$.total").value(4))
			.andExpect(jsonPath("$.totalPages").value(2))
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].name").value("Autre client"));
		this.mvc.perform(get("/api/customers").with(manager()).param("sort", "phone,asc"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("UNKNOWN_SORT_FIELD"));
	}

	@Test
	void filtersCustomersWithAnUnpaidDebtServerSide() throws Exception {
		String owing = idOf(create(manager(), "{\"name\": \"Dette Ouverte\", \"creditLimit\": 100000}"));
		String settled = idOf(create(manager(), "{\"name\": \"Dette Soldée\", \"creditLimit\": 100000}"));
		idOf(create(manager(), "{\"name\": \"Dette Jamais\"}"));
		entry(owing, "CREDIT_SALE", "DEBIT", 40000, 40000, "SALE", 60);
		entry(owing, "PAYMENT", "CREDIT", 15000, 25000, "CUSTOMER_PAYMENT", 30);
		// Dette entièrement réglée : solde 0, donc plus « impayée ».
		entry(settled, "CREDIT_SALE", "DEBIT", 10000, 10000, "SALE", 60);
		entry(settled, "PAYMENT", "CREDIT", 10000, 0, "CUSTOMER_PAYMENT", 30);

		this.mvc.perform(get("/api/customers").with(manager()).param("q", "Dette").param("withDebt", "true"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].name").value("Dette Ouverte"))
			.andExpect(jsonPath("$.items[0].balance").value(25000));
		this.mvc.perform(get("/api/customers").with(manager()).param("q", "Dette").param("withDebt", "false"))
			.andExpect(jsonPath("$.total").value(2))
			.andExpect(jsonPath("$.items[*].name", containsInAnyOrder("Dette Soldée", "Dette Jamais")));
		this.mvc.perform(get("/api/customers").with(manager()).param("q", "Dette"))
			.andExpect(jsonPath("$.total").value(3));
		// Combiné à la pagination : le total compte bien les seuls débiteurs.
		this.mvc.perform(get("/api/customers").with(manager()).param("q", "Dette").param("withDebt", "true")
			.param("page", "0").param("size", "10"))
			.andExpect(jsonPath("$.total").value(1));
		this.mvc.perform(get("/api/customers").with(manager()).param("withDebt", "peut-être"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void validatesServerSide() throws Exception {
		create(manager(), """
				{"code": "CLI 01", "name": "", "phone": "appeler", "email": "pas-un-email", "creditLimit": -1,
				 "paymentTermsDays": 400}""")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("code", "name", "phone", "email",
					"creditLimit", "paymentTermsDays")));
		create(manager(), "{\"name\": \"Décimales\", \"creditLimit\": 10.555}").andExpect(status().isBadRequest());
		create(manager(), "{\"name\": \"Type\", \"type\": \"ROBOT\"}").andExpect(status().isBadRequest());

		create(manager(), "{\"code\": \"CLI-DOUBLE\", \"name\": \"Premier\"}").andExpect(status().isCreated());
		create(manager(), "{\"code\": \"cli-double\", \"name\": \"Second\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CUSTOMER_CODE_ALREADY_USED"));

		this.mvc.perform(get("/api/customers/{id}", UUID.randomUUID()).with(manager()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));
	}

	@Test
	void permissionsAreEnforced() throws Exception {
		String id = idOf(create(manager(), "{\"name\": \"Client droits\"}"));

		RequestPostProcessor reader = actor(Permissions.CUSTOMER_READ);
		this.mvc.perform(get("/api/customers/{id}", id).with(reader)).andExpect(status().isOk());
		create(reader, "{\"name\": \"Interdit\"}").andExpect(status().isForbidden());
		edit(reader, id, "{\"name\": \"Interdit\"}").andExpect(status().isForbidden());

		RequestPostProcessor editor = actor(Permissions.CUSTOMER_READ, Permissions.CUSTOMER_UPDATE);
		this.mvc.perform(delete("/api/customers/{id}", id).with(editor)).andExpect(status().isForbidden());

		this.mvc.perform(get("/api/customers").with(actor(Permissions.PRODUCT_READ))).andExpect(status().isForbidden());
	}

	@Test
	void theAccountShowsTheDebtAndTheAvailableCredit() throws Exception {
		String id = idOf(create(manager(), "{\"name\": \"Client crédit\", \"creditLimit\": 100000, \"paymentTermsDays\": 30}"));
		this.mvc.perform(get("/api/customers/{id}/account", id).with(manager()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.balance").value(0))
			.andExpect(jsonPath("$.availableCredit").value(100000))
			.andExpect(jsonPath("$.paymentTermsDays").value(30));

		// Écritures telles que les poseront ventes et paiements.
		entry(id, "CREDIT_SALE", "DEBIT", 40000, 40000, "SALE", 60);
		entry(id, "PAYMENT", "CREDIT", 15000, 25000, "CUSTOMER_PAYMENT", 0);

		this.mvc.perform(get("/api/customers/{id}", id).with(manager()))
			.andExpect(jsonPath("$.balance").value(25000))
			.andExpect(jsonPath("$.availableCredit").value(75000));
		this.mvc.perform(get("/api/customers").with(manager()).param("q", "Client crédit"))
			.andExpect(jsonPath("$.items[0].balance").value(25000));
		this.mvc.perform(get("/api/customers/{id}/account/entries", id).with(manager()).param("sort", "createdAt,asc"))
			.andExpect(jsonPath("$.total").value(2))
			.andExpect(jsonPath("$.items[0].type").value("CREDIT_SALE"))
			.andExpect(jsonPath("$.items[0].direction").value("DEBIT"))
			.andExpect(jsonPath("$.items[1].type").value("PAYMENT"))
			.andExpect(jsonPath("$.items[1].balanceAfter").value(25000))
			.andExpect(jsonPath("$.items[1].createdByUserId").value(this.user.toString()));
	}

	@Test
	void theDatabaseImposesTheDirectionOfEachEntryType() throws Exception {
		String id = idOf(create(manager(), "{\"name\": \"Client sens\"}"));

		// Une vente à crédit ne peut pas réduire la dette.
		assertThatThrownBy(() -> entry(id, "CREDIT_SALE", "CREDIT", 500, 500, "SALE", 0))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("ck_customer_account_entry_direction");
	}

	@Test
	void theDatabaseRefusesANegativeBalance() throws Exception {
		String id = idOf(create(manager(), "{\"name\": \"Client trop-perçu\"}"));

		assertThatThrownBy(() -> entry(id, "PAYMENT", "CREDIT", 2000, -1000, "CUSTOMER_PAYMENT", 0))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("ck_customer_account_entry_balance_after");
	}

	@Test
	void aDocumentCannotBePostedTwiceAndEntriesAreAppendOnly() throws Exception {
		String id = idOf(create(manager(), "{\"name\": \"Client journal\"}"));
		UUID sale = UUID.randomUUID();
		insertEntry(id, sale, 1000, 1000);

		assertThatThrownBy(() -> insertEntry(id, sale, 1000, 2000))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("uk_customer_account_entry_source");
	}

	@Test
	void entriesCannotBeModified() throws Exception {
		String id = idOf(create(manager(), "{\"name\": \"Client immuable\"}"));
		entry(id, "CREDIT_SALE", "DEBIT", 1000, 1000, "SALE", 0);

		assertThatThrownBy(() -> this.jdbc.update("update customer_account_entry set amount = 1 where customer_id = ?::uuid",
				id))
			.hasStackTraceContaining("La table customer_account_entry est en ajout seul");
	}

	// --- Outils ------------------------------------------------------------------------------------

	/** Écriture datée de {@code secondsAgo} secondes avant le début de la transaction. */
	private void entry(String customerId, String type, String direction, int amount, int balanceAfter,
			String sourceType, int secondsAgo) {
		this.jdbc.update("""
				insert into customer_account_entry (customer_id, entry_type, direction, amount, balance_after, source_type,
				                                    source_id, created_at, created_by)
				values (?::uuid, ?, ?, ?, ?, ?, ?, now() - make_interval(secs => ?), ?)""", customerId, type, direction,
				amount, balanceAfter, sourceType, UUID.randomUUID(), secondsAgo, this.user);
	}

	private void insertEntry(String customerId, UUID sale, int amount, int balanceAfter) {
		this.jdbc.update("""
				insert into customer_account_entry (customer_id, entry_type, direction, amount, balance_after, source_type,
				                                    source_id, created_by)
				values (?::uuid, 'CREDIT_SALE', 'DEBIT', ?, ?, 'SALE', ?, ?)""", customerId, amount, balanceAfter, sale,
				this.user);
	}

	private ResultActions create(RequestPostProcessor actor, String json) throws Exception {
		return this.mvc.perform(post("/api/customers").with(actor).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private ResultActions edit(RequestPostProcessor actor, String id, String json) throws Exception {
		return this.mvc.perform(patch("/api/customers/{id}", id).with(actor).contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}

	private RequestPostProcessor manager() {
		return actor(Permissions.ALL.toArray(String[]::new));
	}

	private RequestPostProcessor actor(String... permissions) {
		return authentication(StockFixtures.authentication(this.user, permissions));
	}

	private static String idOf(ResultActions result) throws Exception {
		String body = result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		int start = body.indexOf("\"id\":\"") + 6;
		return body.substring(start, body.indexOf('"', start));
	}

}
