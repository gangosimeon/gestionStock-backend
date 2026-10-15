package com.geststock.customer;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.geststock.support.PostgresTestConfiguration;
import com.geststock.support.WithPermissionsSecurityContextFactory;
import com.geststock.user.UserService;
import com.geststock.user.dto.CreateUserRequest;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Créances clients et dépôts (D6), avec de vrais jetons : le solde et le plafond restent ceux de
 * l'entreprise ; le relevé détaillé, les règlements et le rapport clients ne montrent que le
 * périmètre de l'utilisateur, le reste en une ligne agrégée sans détail.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@Transactional
class CustomerAccountScopeIT {

	private static final String PASSWORD = "Optique2026";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private UserService users;

	@Autowired
	private JsonMapper json;

	private UUID plateau;

	private UUID cocody;

	private UUID customer;

	private String admin;

	private String cashier;

	private String saleInCocody;

	@BeforeEach
	void setUp() throws Exception {
		this.plateau = this.jdbc.queryForObject(
				"insert into warehouse (code, name) values ('CA-PLT', 'Créances Plateau') returning id", UUID.class);
		this.cocody = this.jdbc.queryForObject(
				"insert into warehouse (code, name) values ('CA-COC', 'Créances Cocody') returning id", UUID.class);
		UUID category = this.jdbc.queryForObject("select id from category where name = 'Montures'", UUID.class);
		UUID frame = this.jdbc.queryForObject("""
				insert into product (sku, name, category_id, purchase_price, retail_price, wholesale_price)
				values ('CA-MON-1', 'Monture créances', ?, 10000, 20000, 18000) returning id""", UUID.class, category);
		this.customer = this.jdbc.queryForObject("""
				insert into customer (code, name, credit_limit, price_category)
				values ('CLI-CA', 'Client créances', 500000, 'RETAIL') returning id""", UUID.class);
		createUser("ca_admin", "ADMIN", List.of(this.plateau, this.cocody));
		createUser("ca_caisse", "CAISSIER", List.of(this.plateau));
		this.admin = accessToken("ca_admin");
		this.cashier = accessToken("ca_caisse");

		// Plateau : vente à crédit de 40 000. Cocody : vente à crédit de 20 000, réglée de 5 000.
		for (UUID warehouse : List.of(this.plateau, this.cocody)) {
			ok(at(warehouse, this.admin, post("/api/stock/movements")),
					"{\"productId\": \"%s\", \"quantity\": 5, \"reason\": \"SUPPLY\"}".formatted(frame));
		}
		ok(at(this.plateau, this.admin, post("/api/sales")), """
				{"customerId": "%s", "paidAmount": 0, "items": [{"productId": "%s", "quantity": 2}]}"""
			.formatted(this.customer, frame));
		this.saleInCocody = this.json.readTree(ok(at(this.cocody, this.admin, post("/api/sales")), """
				{"customerId": "%s", "paidAmount": 0, "items": [{"productId": "%s", "quantity": 1}]}"""
			.formatted(this.customer, frame)).andReturn().getResponse().getContentAsString()).get("number").asString();
		ok(at(this.cocody, this.admin, post("/api/cash-register/open")), "{\"openingBalance\": 0}");
		ok(at(this.cocody, this.admin, post("/api/customers/" + this.customer + "/payments")),
				"{\"amount\": 5000, \"paymentMethod\": \"CASH\", \"note\": \"Acompte Cocody\"}");
	}

	@Test
	void everyNewEntryCarriesTheWarehouseOfItsDocument() {
		assertThat(this.jdbc.queryForList("""
				select entry_type || '@' || coalesce(w.code, 'AUCUN') from customer_account_entry e
				left join warehouse w on w.id = e.warehouse_id where e.customer_id = ? order by e.created_at""",
				String.class, this.customer))
			.containsExactly("CREDIT_SALE@CA-PLT", "CREDIT_SALE@CA-COC", "PAYMENT@CA-COC");
	}

	@Test
	void theDatabaseRefusesANewEntryWithoutWarehouseOrOutsideTheWarehouseOfItsDocument() {
		UUID sale = this.jdbc.queryForObject("select id from sale where number = ?", UUID.class, this.saleInCocody);
		UUID author = this.jdbc.queryForObject("select id from app_user where username = 'ca_admin'", UUID.class);
		String insert = """
				insert into customer_account_entry (customer_id, entry_type, direction, amount, balance_after, source_type,
				                                    source_id, created_by, warehouse_id)
				values (?, 'SALE_CANCELLATION', 'CREDIT', 1000, 54000, 'SALE', ?, ?, ?)""";

		this.jdbc.execute("savepoint sans_depot");
		this.jdbc.update(insert, this.customer, sale, author, null);
		assertThatThrownBy(() -> this.jdbc.execute("set constraints trg_customer_account_entry_warehouse immediate"))
			.hasStackTraceContaining("est obligatoire");
		this.jdbc.execute("rollback to savepoint sans_depot");

		// Vente de Cocody imputée au Plateau : refusée.
		this.jdbc.update(insert, this.customer, sale, author, this.plateau);
		assertThatThrownBy(() -> this.jdbc.execute("set constraints trg_customer_account_entry_warehouse immediate"))
			.hasStackTraceContaining("n'est pas dans le dépôt de sa pièce");
	}

	@Test
	void aRestrictedUserSeesTheCompanyBalanceButOnlyTheDetailsOfHisWarehouses() throws Exception {
		// Solde et plafond : ceux de l'entreprise ; la part des autres dépôts en une ligne agrégée.
		this.mvc.perform(at(this.plateau, this.cashier, get("/api/customers/" + this.customer + "/account")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.balance").value(55000))
			.andExpect(jsonPath("$.availableCredit").value(445000))
			.andExpect(jsonPath("$.otherWarehousesBalance").value(15000));

		// Relevé : seulement l'écriture du Plateau, sans solde après écriture ; rien de Cocody.
		this.mvc.perform(at(this.plateau, this.cashier, get("/api/customers/" + this.customer + "/account/entries")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].type").value("CREDIT_SALE"))
			.andExpect(jsonPath("$.items[0].amount").value(40000))
			.andExpect(jsonPath("$.items[0].warehouseId").value(this.plateau.toString()))
			.andExpect(jsonPath("$.items[0].balanceAfter").value(nullValue()))
			.andExpect(content().string(not(containsString(this.saleInCocody))));

		// Règlements : celui de Cocody n'apparaît pas.
		this.mvc.perform(at(this.plateau, this.cashier, get("/api/customers/" + this.customer + "/payments")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(0))
			.andExpect(content().string(not(containsString("Acompte Cocody"))));
	}

	@Test
	void aUserWhoSeesEveryWarehouseGetsTheFullStatement() throws Exception {
		this.mvc.perform(at(this.plateau, this.admin, get("/api/customers/" + this.customer + "/account")))
			.andExpect(jsonPath("$.balance").value(55000))
			.andExpect(jsonPath("$.otherWarehousesBalance").value(nullValue()));
		this.mvc.perform(at(this.plateau, this.admin, get("/api/customers/" + this.customer + "/account/entries")))
			.andExpect(jsonPath("$.total").value(3))
			.andExpect(jsonPath("$.items[0].balanceAfter").isNumber());
		this.mvc.perform(at(this.plateau, this.admin, get("/api/customers/" + this.customer + "/payments")))
			.andExpect(jsonPath("$.total").value(1));
	}

	@Test
	void theCustomerReportSeparatesTheCompanyDebtFromTheReportWarehouse() throws Exception {
		String report = "/api/reports/customers?customerId=" + this.customer;
		this.mvc.perform(at(this.cocody, this.admin, get(report)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].balance").value(55000))
			.andExpect(jsonPath("$.items[0].warehouseBalance").value(15000))
			.andExpect(jsonPath("$.items[0].paymentsAmount").value(5000))
			.andExpect(jsonPath("$.summary.warehouseBalance").value(15000));
		this.mvc.perform(at(this.plateau, this.admin, get(report)))
			.andExpect(jsonPath("$.items[0].balance").value(55000))
			.andExpect(jsonPath("$.items[0].warehouseBalance").value(40000))
			.andExpect(jsonPath("$.items[0].paymentsAmount").value(0));
		this.mvc.perform(at(this.plateau, this.admin, get(report + "&allWarehouses=true")))
			.andExpect(jsonPath("$.items[0].warehouseBalance").value(nullValue()))
			.andExpect(jsonPath("$.items[0].paymentsAmount").value(5000));
	}

	// --- Outils ---------------------------------------------------------------------------------

	private static MockHttpServletRequestBuilder at(UUID warehouse, String token, MockHttpServletRequestBuilder request) {
		return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("X-Warehouse-Id", warehouse.toString());
	}

	private ResultActions ok(MockHttpServletRequestBuilder request, String body) throws Exception {
		return this.mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().is2xxSuccessful());
	}

	private void createUser(String username, String role, List<UUID> warehouses) {
		WithPermissionsSecurityContextFactory.runWithAllPermissions(() -> this.users.create(new CreateUserRequest(username,
				null, "Test", null, null, null, PASSWORD, List.of(role), null, warehouses.get(0), warehouses)));
	}

	private String accessToken(String username) throws Exception {
		ResultActions login = this.mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\": \"" + username + "\", \"password\": \"" + PASSWORD + "\"}"))
			.andExpect(status().isOk());
		return this.json.readTree(login.andReturn().getResponse().getContentAsString()).get("accessToken").asString();
	}

}
