package com.geststock.warehouse;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Écritures limitées au dépôt courant (D2, D3), avec de vrais jetons : un gestionnaire affecté
 * aux dépôts A et B, positionné sur A, lit les pièces de B mais n'agit jamais dessus (409
 * {@code WAREHOUSE_MISMATCH}, rien n'est modifié) ; depuis B, les mêmes actions passent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@Transactional
class CurrentWarehouseWriteIT {

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

	private UUID frame;

	private UUID customer;

	private UUID supplier;

	private String manager;

	@BeforeEach
	void setUp() throws Exception {
		this.plateau = this.jdbc.queryForObject(
				"insert into warehouse (code, name) values ('CW-PLT', 'Écritures Plateau') returning id", UUID.class);
		this.cocody = this.jdbc.queryForObject(
				"insert into warehouse (code, name) values ('CW-COC', 'Écritures Cocody') returning id", UUID.class);
		UUID category = this.jdbc.queryForObject("select id from category where name = 'Montures'", UUID.class);
		this.frame = this.jdbc.queryForObject("""
				insert into product (sku, name, category_id, purchase_price, retail_price, wholesale_price)
				values ('CW-MON-1', 'Monture écritures', ?, 10000, 20000, 18000) returning id""", UUID.class, category);
		this.customer = this.jdbc.queryForObject("""
				insert into customer (code, name, credit_limit, price_category)
				values ('CLI-CW', 'Client écritures', 500000, 'RETAIL') returning id""", UUID.class);
		this.supplier = this.jdbc.queryForObject("""
				insert into supplier (code, name, delivery_lead_time_days, payment_terms_days)
				values ('FOU-CW', 'Fournisseur écritures', 5, 30) returning id""", UUID.class);
		createUser("cw_gest", "GESTIONNAIRE");
		this.manager = accessToken("cw_gest");
		for (UUID warehouse : List.of(this.plateau, this.cocody)) {
			created(at(warehouse, post("/api/stock/movements")), """
					{"productId": "%s", "quantity": 10, "reason": "SUPPLY"}""".formatted(this.frame));
		}
	}

	@Test
	void aManagerOfTwoWarehousesReadsButNeverWritesTheOtherOne() throws Exception {
		// Pièces du dépôt B (Cocody), créées depuis B.
		String movement = this.jdbc.queryForObject(
				"select id::text from stock_movement where warehouse_id = ? and product_id = ?", String.class, this.cocody,
				this.frame);
		String sale = created(at(this.cocody, post("/api/sales")), """
				{"customerId": "%s", "paidAmount": 0, "items": [{"productId": "%s", "quantity": 2}]}"""
			.formatted(this.customer, this.frame));
		String order = created(at(this.cocody, post("/api/purchases/orders")), """
				{"supplierId": "%s", "lines": [{"productId": "%s", "quantity": 3}]}""".formatted(this.supplier, this.frame));
		String inventory = created(at(this.cocody, post("/api/inventories")), """
				{"scope": "SELECTION", "productIds": ["%s"]}""".formatted(this.frame));
		String appointment = created(at(this.cocody, post("/api/appointments")), appointment("Awa Koné"));

		// Lecture par identifiant depuis A : permise (dépôt du périmètre).
		for (String path : List.of("/api/sales/" + sale, "/api/purchases/orders/" + order,
				"/api/stock/movements/" + movement, "/api/inventories/" + inventory, "/api/appointments/" + appointment)) {
			this.mvc.perform(at(this.plateau, get(path))).andExpect(status().isOk());
		}

		// Écritures depuis A sur les pièces de B : toutes refusées.
		mismatch(at(this.plateau, post("/api/sales/" + sale + "/cancel")), "{\"reason\": \"Erreur\"}");
		mismatch(at(this.plateau, post("/api/sales/" + sale + "/returns")), """
				{"settlement": "ACCOUNT_CREDIT", "reason": "Taille",
				 "items": [{"productId": "%s", "quantity": 1, "condition": "RESELLABLE"}]}""".formatted(this.frame));
		mismatch(at(this.plateau, post("/api/purchases/orders/" + order + "/receive")), "{}");
		mismatch(at(this.plateau, post("/api/purchases/orders/" + order + "/cancel")), "{}");
		mismatch(at(this.plateau, post("/api/stock/movements/" + movement + "/reversal")), "{}");
		mismatch(at(this.plateau, post("/api/inventories/" + inventory + "/start")), null);
		mismatch(at(this.plateau, post("/api/inventories/" + inventory + "/cancel")), "{}");
		mismatch(at(this.plateau, put("/api/appointments/" + appointment)), appointment("Awa Koné (déplacé)"));
		mismatch(at(this.plateau, patch("/api/appointments/" + appointment + "/status")),
				"{\"status\": \"CANCELLED\", \"reason\": \"Erreur\"}");
		// Transfert : la source doit être le dépôt courant.
		mismatch(at(this.plateau, post("/api/warehouses/transfer")), """
				{"fromWarehouseId": "%s", "toWarehouseId": "%s", "productId": "%s", "quantity": 1}"""
			.formatted(this.cocody, this.plateau, this.frame));
		// Corps de requête : un dépôt différent du dépôt courant ne le remplace jamais.
		mismatch(at(this.plateau, post("/api/sales")), """
				{"warehouseId": "%s", "customerId": "%s", "paidAmount": 0, "items": [{"productId": "%s", "quantity": 1}]}"""
			.formatted(this.cocody, this.customer, this.frame));
		mismatch(at(this.plateau, post("/api/inventories")), """
				{"warehouseId": "%s", "scope": "SELECTION", "productIds": ["%s"]}""".formatted(this.cocody, this.frame));
		mismatch(at(this.plateau, post("/api/purchases/orders")), """
				{"supplierId": "%s", "warehouseId": "%s", "lines": [{"productId": "%s", "quantity": 1}]}"""
			.formatted(this.supplier, this.cocody, this.frame));

		// Aucun effet de bord : stock, statuts et nombre de pièces inchangés.
		assertThat(level(this.cocody)).isEqualTo(8);
		assertThat(level(this.plateau)).isEqualTo(10);
		assertThat(text("select status from sale where id = ?::uuid", sale)).isEqualTo("COMPLETED");
		assertThat(text("select status from purchase_order where id = ?::uuid", order)).isEqualTo("ORDERED");
		assertThat(text("select status from inventory_session where id = ?::uuid", inventory)).isEqualTo("DRAFT");
		assertThat(text("select status from appointment where id = ?::uuid", appointment)).isEqualTo("SCHEDULED");
		assertThat(count("select count(*) from customer_return where sale_id = ?::uuid", sale)).isZero();
		assertThat(count("select count(*) from stock_movement where reversal_of_id = ?::uuid", movement)).isZero();
		assertThat(count("select count(*) from sale where warehouse_id = ?", this.plateau)).isZero();
		assertThat(count("select count(*) from inventory_session where warehouse_id = ?", this.plateau)).isZero();
		assertThat(count("select count(*) from purchase_order where warehouse_id = ?", this.plateau)).isZero();

		// Depuis B : les mêmes actions passent.
		this.mvc.perform(body(at(this.cocody, post("/api/sales/" + sale + "/cancel")), "{\"reason\": \"Erreur\"}"))
			.andExpect(status().isOk());
		this.mvc.perform(body(at(this.cocody, post("/api/purchases/orders/" + order + "/receive")), "{}"))
			.andExpect(status().isOk());
		this.mvc.perform(body(at(this.cocody, post("/api/warehouses/transfer")), """
				{"fromWarehouseId": "%s", "toWarehouseId": "%s", "productId": "%s", "quantity": 1}"""
			.formatted(this.cocody, this.plateau, this.frame)))
			.andExpect(status().isOk());
		assertThat(level(this.cocody)).as("10 − 2 vendus + 2 annulés + 3 reçus − 1 transféré").isEqualTo(12);
		assertThat(level(this.plateau)).isEqualTo(11);
	}

	@Test
	void theRuleAlsoBindsAnAdministratorWhoSeesEveryWarehouse() throws Exception {
		createUser("cw_admin", "ADMIN");
		String admin = accessToken("cw_admin");
		String expense = created(at(this.cocody, post("/api/expenses")), """
				{"category": "TRANSPORT", "label": "Livraison", "amount": 5000, "paymentMethod": "BANK_TRANSFER"}""");

		this.mvc.perform(at(this.plateau, post("/api/expenses/" + expense + "/cancel"), admin)
			.contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"Doublon\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("WAREHOUSE_MISMATCH"));
		assertThat(text("select status from expense where id = ?::uuid", expense)).isEqualTo("PAID");

		this.mvc.perform(at(this.cocody, post("/api/expenses/" + expense + "/cancel"), admin)
			.contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"Doublon\"}"))
			.andExpect(status().isOk());
	}

	@Test
	void aRecordOutsideThePerimeterStaysNotFoundRatherThanMismatched() throws Exception {
		UUID other = this.jdbc.queryForObject(
				"insert into warehouse (code, name) values ('CW-OUT', 'Écritures hors périmètre') returning id", UUID.class);
		createUser("cw_admin2", "ADMIN");
		String admin = accessToken("cw_admin2");
		created(at(other, post("/api/stock/movements"), admin), """
				{"productId": "%s", "quantity": 4, "reason": "SUPPLY"}""".formatted(this.frame));
		String foreign = this.jdbc.queryForObject(
				"select id::text from stock_movement where warehouse_id = ? and product_id = ?", String.class, other,
				this.frame);

		// Le gestionnaire (A et B) ne voit pas ce mouvement : 404, jamais 409 (existence non révélée).
		this.mvc.perform(body(at(this.plateau, post("/api/stock/movements/" + foreign + "/reversal")), "{}"))
			.andExpect(status().isNotFound());
		assertThat(level(other)).isEqualTo(4);
	}

	// --- Outils ---------------------------------------------------------------------------------

	private String appointment(String name) {
		String when = LocalDateTime.now().plusDays(3).withHour(10).withMinute(0).withSecond(0).withNano(0)
			.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
		return """
				{"customerName": "%s", "phone": "07 00 00 00 00", "dateTime": "%s"}""".formatted(name, when);
	}

	private MockHttpServletRequestBuilder at(UUID warehouse, MockHttpServletRequestBuilder request) {
		return at(warehouse, request, this.manager);
	}

	private static MockHttpServletRequestBuilder at(UUID warehouse, MockHttpServletRequestBuilder request, String token) {
		return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("X-Warehouse-Id", warehouse.toString());
	}

	private static MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, String json) {
		return json == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private void mismatch(MockHttpServletRequestBuilder request, String json) throws Exception {
		this.mvc.perform(body(request, json))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("WAREHOUSE_MISMATCH"));
	}

	private String created(MockHttpServletRequestBuilder request, String json) throws Exception {
		ResultActions result = this.mvc.perform(body(request, json)).andExpect(status().isCreated());
		return this.json.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asString();
	}

	private int level(UUID warehouse) {
		List<Integer> found = this.jdbc.queryForList(
				"select quantity from stock_level where warehouse_id = ? and product_id = ?", Integer.class, warehouse,
				this.frame);
		return found.isEmpty() ? 0 : found.get(0);
	}

	private String text(String sql, String id) {
		return this.jdbc.queryForObject(sql, String.class, id);
	}

	private long count(String sql, Object arg) {
		return this.jdbc.queryForObject(sql, Long.class, arg);
	}

	private void createUser(String username, String role) {
		WithPermissionsSecurityContextFactory.runWithAllPermissions(() -> this.users.create(new CreateUserRequest(username,
				null, "Test", null, null, null, PASSWORD, List.of(role), null, this.plateau,
				List.of(this.plateau, this.cocody))));
	}

	private String accessToken(String username) throws Exception {
		ResultActions login = this.mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\": \"" + username + "\", \"password\": \"" + PASSWORD + "\"}"))
			.andExpect(status().isOk());
		return this.json.readTree(login.andReturn().getResponse().getContentAsString()).get("accessToken").asString();
	}

}
