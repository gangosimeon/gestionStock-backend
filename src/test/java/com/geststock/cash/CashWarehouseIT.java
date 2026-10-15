package com.geststock.cash;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Une caisse ne sert que son dépôt (D5), avec de vrais jetons : un gestionnaire affecté aux
 * dépôts A et B ouvre sa caisse dans A ; depuis B, elle est signalée hors du dépôt courant et
 * aucune opération ne la touche (409 {@code CASH_SESSION_OTHER_WAREHOUSE}, rien n'est écrit).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@Transactional
class CashWarehouseIT {

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

	private UUID supplier;

	private UUID manager;

	private String token;

	@BeforeEach
	void setUp() throws Exception {
		this.plateau = this.jdbc.queryForObject(
				"insert into warehouse (code, name) values ('CS-PLT', 'Caisse Plateau') returning id", UUID.class);
		this.cocody = this.jdbc.queryForObject(
				"insert into warehouse (code, name) values ('CS-COC', 'Caisse Cocody') returning id", UUID.class);
		this.customer = this.jdbc.queryForObject("""
				insert into customer (code, name, credit_limit, price_category)
				values ('CLI-CS', 'Client caisse', 500000, 'RETAIL') returning id""", UUID.class);
		this.supplier = this.jdbc.queryForObject("""
				insert into supplier (code, name, delivery_lead_time_days, payment_terms_days)
				values ('FOU-CS', 'Fournisseur caisse', 5, 30) returning id""", UUID.class);
		this.manager = WithPermissionsSecurityContextFactory.runWithAllPermissions(() -> this.users.create(
				new CreateUserRequest("cs_gest", null, "Test", null, null, null, PASSWORD, List.of("GESTIONNAIRE"), null,
						this.plateau, List.of(this.plateau, this.cocody)))
			.id());
		this.token = accessToken("cs_gest");
		this.mvc.perform(body(at(this.plateau, post("/api/cash-register/open")), "{\"openingBalance\": 50000}"))
			.andExpect(status().isCreated());
	}

	@Test
	void aDrawerOpenedInAnotherWarehouseIsReportedAndCannotBeUsed() throws Exception {
		String entry = id(this.mvc.perform(body(at(this.plateau, post("/api/cash-register/operations")),
				"{\"type\": \"IN\", \"amount\": 1000, \"note\": \"Monnaie\"}")).andExpect(status().isCreated()));

		// Depuis B : la caisse est connue, mais signalée hors du dépôt courant.
		this.mvc.perform(at(this.cocody, get("/api/cash-register/current")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.warehouseId").value(this.plateau.toString()))
			.andExpect(jsonPath("$.inCurrentWarehouse").value(false));

		otherWarehouse(post("/api/cash-register/operations"), "{\"type\": \"OUT\", \"amount\": 500}");
		otherWarehouse(post("/api/cash-register/operations/" + entry + "/reversal"), "{\"reason\": \"Erreur\"}");
		otherWarehouse(post("/api/cash-register/close"), "{\"countedCash\": 51000}");
		otherWarehouse(post("/api/customers/" + this.customer + "/payments"),
				"{\"amount\": 1000, \"paymentMethod\": \"MOBILE_MONEY\"}");
		otherWarehouse(post("/api/suppliers/" + this.supplier + "/payments"),
				"{\"amount\": 1000, \"paymentMethod\": \"CASH\"}");

		// Rien n'a été écrit : caisse ouverte, une seule écriture, aucun règlement.
		assertThat(this.jdbc.queryForObject("select status from cash_session where cashier_id = ?", String.class,
				this.manager)).isEqualTo("OPEN");
		assertThat(this.jdbc.queryForObject("""
				select count(*) from cash_entry e join cash_session s on s.id = e.session_id where s.cashier_id = ?""",
				Integer.class, this.manager)).isEqualTo(1);
		assertThat(this.jdbc.queryForObject("select count(*) from supplier_payment where supplier_id = ?", Integer.class,
				this.supplier)).isZero();
		assertThat(this.jdbc.queryForObject("select count(*) from customer_account_entry where customer_id = ?",
				Integer.class, this.customer)).isZero();

		// Depuis A : la caisse est la caisse courante, et se clôture normalement.
		this.mvc.perform(at(this.plateau, get("/api/cash-register/current")))
			.andExpect(jsonPath("$.inCurrentWarehouse").value(true));
		this.mvc.perform(body(at(this.plateau, post("/api/cash-register/close")), "{\"countedCash\": 51000}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.difference").value(0));
	}

	@Test
	void theDatabaseRefusesACashEntryForADocumentOfAnotherWarehouse() throws Exception {
		UUID category = this.jdbc.queryForObject("select id from category where name = 'Montures'", UUID.class);
		UUID frame = this.jdbc.queryForObject("""
				insert into product (sku, name, category_id, purchase_price, retail_price, wholesale_price)
				values ('CS-MON-1', 'Monture caisse', ?, 10000, 20000, 18000) returning id""", UUID.class, category);
		this.mvc.perform(body(at(this.cocody, post("/api/stock/movements")),
				"{\"productId\": \"%s\", \"quantity\": 5, \"reason\": \"SUPPLY\"}".formatted(frame)))
			.andExpect(status().isCreated());
		UUID sale = UUID.fromString(id(this.mvc.perform(body(at(this.cocody, post("/api/sales")), """
				{"customerId": "%s", "paidAmount": 0, "items": [{"productId": "%s", "quantity": 1}]}"""
			.formatted(this.customer, frame))).andExpect(status().isCreated())));
		UUID session = this.jdbc.queryForObject("select id from cash_session where cashier_id = ?", UUID.class,
				this.manager);

		// Vente du dépôt B encaissée à la main dans la caisse du dépôt A : refusée au COMMIT.
		this.jdbc.update("""
				insert into cash_entry (number, session_id, entry_type, direction, payment_method, amount, source_type,
				                        source_id, source_reference, created_at, created_by)
				values ('OPC-CS-1', ?, 'SALE', 'IN', 'CASH', 100, 'SALE', ?, 'VTE-CS', now(), ?)""", session, sale,
				this.manager);
		assertThatThrownBy(() -> this.jdbc.execute("set constraints trg_cash_entry_warehouse_consistency immediate"))
			.hasStackTraceContaining("est d'un autre dépôt que la caisse");
	}

	// --- Outils ---------------------------------------------------------------------------------

	private void otherWarehouse(MockHttpServletRequestBuilder request, String json) throws Exception {
		this.mvc.perform(body(at(this.cocody, request), json))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CASH_SESSION_OTHER_WAREHOUSE"));
	}

	private MockHttpServletRequestBuilder at(UUID warehouse, MockHttpServletRequestBuilder request) {
		return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + this.token)
			.header("X-Warehouse-Id", warehouse.toString());
	}

	private static MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, String json) {
		return request.contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private String id(ResultActions result) throws Exception {
		return this.json.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asString();
	}

	private String accessToken(String username) throws Exception {
		ResultActions login = this.mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\": \"" + username + "\", \"password\": \"" + PASSWORD + "\"}"))
			.andExpect(status().isOk());
		return this.json.readTree(login.andReturn().getResponse().getContentAsString()).get("accessToken").asString();
	}

}
