package com.geststock.purchase;

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
 * Paiements fournisseurs et dépôts (D7), avec de vrais jetons : un paiement appartient au dépôt
 * courant, ne s'impute que sur les commandes de ce dépôt, et n'est listé qu'aux utilisateurs de
 * ce dépôt (tous avec warehouse:all).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@Transactional
class SupplierPaymentScopeIT {

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

	private UUID supplier;

	private String admin;

	private String manager;

	private String orderInPlateau;

	private String orderInCocody;

	@BeforeEach
	void setUp() throws Exception {
		this.plateau = this.jdbc.queryForObject(
				"insert into warehouse (code, name) values ('SP-PLT', 'Fournisseurs Plateau') returning id", UUID.class);
		this.cocody = this.jdbc.queryForObject(
				"insert into warehouse (code, name) values ('SP-COC', 'Fournisseurs Cocody') returning id", UUID.class);
		UUID category = this.jdbc.queryForObject("select id from category where name = 'Montures'", UUID.class);
		UUID frame = this.jdbc.queryForObject("""
				insert into product (sku, name, category_id, purchase_price, retail_price, wholesale_price)
				values ('SP-MON-1', 'Monture fournisseurs', ?, 10000, 20000, 18000) returning id""", UUID.class, category);
		this.supplier = this.jdbc.queryForObject("""
				insert into supplier (code, name, delivery_lead_time_days, payment_terms_days)
				values ('FOU-SP', 'Fournisseur dépôts', 5, 30) returning id""", UUID.class);
		createUser("sp_admin", "ADMIN", List.of(this.plateau, this.cocody));
		createUser("sp_gest", "GESTIONNAIRE", List.of(this.plateau));
		this.admin = accessToken("sp_admin");
		this.manager = accessToken("sp_gest");

		// Plateau : commande reçue de 30 000 (la plus ancienne). Cocody : commande reçue de 20 000.
		this.orderInPlateau = receivedOrder(this.plateau, frame, 3);
		this.orderInCocody = receivedOrder(this.cocody, frame, 2);
	}

	@Test
	void aGlobalPaymentOnlySettlesOrdersOfTheCurrentWarehouse() throws Exception {
		String payment = id(pay(this.cocody, this.admin, "{\"amount\": 5000, \"paymentMethod\": \"BANK_TRANSFER\"}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.warehouseId").value(this.cocody.toString()))
			.andExpect(jsonPath("$.allocations.length()").value(1))
			.andExpect(jsonPath("$.allocations[0].orderId").value(this.orderInCocody)));

		// La commande du Plateau, pourtant plus ancienne, n'a rien reçu.
		assertThat(paid(this.orderInPlateau)).isEqualTo("0.00");
		assertThat(paid(this.orderInCocody)).isEqualTo("5000.00");
		assertThat(this.jdbc.queryForObject("select warehouse_id from supplier_payment where id = ?::uuid", UUID.class,
				payment)).isEqualTo(this.cocody);
	}

	@Test
	void theBalanceDueIsTheOneOfTheCurrentWarehouse() throws Exception {
		// 45 000 dus au total, mais 30 000 seulement au Plateau.
		pay(this.plateau, this.admin, "{\"amount\": 40000, \"paymentMethod\": \"BANK_TRANSFER\"}")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("SUPPLIER_PAYMENT_EXCEEDS_BALANCE"))
			.andExpect(jsonPath("$.details.remainingToPay").value(30000));
		assertThat(this.jdbc.queryForObject("select count(*) from supplier_payment where supplier_id = ?", Integer.class,
				this.supplier)).isZero();
	}

	@Test
	void theAccountShowsWhatCanBePaidFromTheCurrentWarehouse() throws Exception {
		this.mvc.perform(at(this.plateau, this.admin, get("/api/suppliers/" + this.supplier + "/account")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.remainingToPay").value(50000))
			.andExpect(jsonPath("$.currentWarehouseRemainingToPay").value(30000));
		this.mvc.perform(at(this.cocody, this.admin, get("/api/suppliers/" + this.supplier + "/account")))
			.andExpect(jsonPath("$.currentWarehouseRemainingToPay").value(20000));
		// Historique d'achats tous dépôts : chaque réception indique son dépôt.
		this.mvc.perform(at(this.plateau, this.admin, get("/api/suppliers/" + this.supplier + "/purchases"))
			.param("allWarehouses", "true"))
			.andExpect(jsonPath("$.total").value(2))
			.andExpect(jsonPath("$.items[*].warehouseId",
					org.hamcrest.Matchers.containsInAnyOrder(this.plateau.toString(), this.cocody.toString())));
	}

	@Test
	void anOrderOfAnotherWarehouseIsNeverPaidFromHere() throws Exception {
		pay(this.plateau, this.admin, """
				{"amount": 5000, "paymentMethod": "BANK_TRANSFER", "orderId": "%s"}""".formatted(this.orderInCocody))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("WAREHOUSE_MISMATCH"));
		assertThat(paid(this.orderInCocody)).isEqualTo("0.00");
	}

	@Test
	void paymentsAreListedToTheUsersOfTheirWarehouseOnly() throws Exception {
		pay(this.cocody, this.admin, "{\"amount\": 5000, \"paymentMethod\": \"BANK_TRANSFER\", \"note\": \"Cocody\"}")
			.andExpect(status().isCreated());
		pay(this.plateau, this.admin, "{\"amount\": 7000, \"paymentMethod\": \"BANK_TRANSFER\", \"note\": \"Plateau\"}")
			.andExpect(status().isCreated());

		String list = "/api/suppliers/" + this.supplier + "/payments";
		this.mvc.perform(at(this.plateau, this.manager, get(list)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].note").value("Plateau"));
		this.mvc.perform(at(this.plateau, this.admin, get(list))).andExpect(jsonPath("$.total").value(2));
	}

	@Test
	void theDatabaseRefusesAPaymentAllocatedToAnOrderOfAnotherWarehouse() {
		UUID payment = this.jdbc.queryForObject("""
				insert into supplier_payment (number, supplier_id, payment_date, amount, payment_method, warehouse_id)
				values ('PAY-SP-1', ?, current_date, 1000, 'BANK_TRANSFER', ?) returning id""", UUID.class, this.supplier,
				this.plateau);
		this.jdbc.update("insert into supplier_payment_allocation (payment_id, purchase_order_id, amount) values (?, ?::uuid, 1000)",
				payment, this.orderInCocody);

		assertThatThrownBy(() -> this.jdbc.execute("set constraints trg_supplier_payment_warehouse immediate"))
			.hasStackTraceContaining("imputé sur la commande d'un autre dépôt");
	}

	// --- Outils ---------------------------------------------------------------------------------

	private String receivedOrder(UUID warehouse, UUID product, int quantity) throws Exception {
		String order = id(this.mvc.perform(body(at(warehouse, this.admin, post("/api/purchases/orders")), """
				{"supplierId": "%s", "lines": [{"productId": "%s", "quantity": %d}]}"""
			.formatted(this.supplier, product, quantity))).andExpect(status().isCreated()));
		this.mvc.perform(body(at(warehouse, this.admin, post("/api/purchases/orders/" + order + "/receive")), "{}"))
			.andExpect(status().isOk());
		return order;
	}

	private ResultActions pay(UUID warehouse, String token, String json) throws Exception {
		return this.mvc.perform(body(at(warehouse, token, post("/api/suppliers/" + this.supplier + "/payments")), json));
	}

	private String paid(String order) {
		return this.jdbc.queryForObject("select paid_amount::text from purchase_order where id = ?::uuid", String.class,
				order);
	}

	private static MockHttpServletRequestBuilder at(UUID warehouse, String token, MockHttpServletRequestBuilder request) {
		return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("X-Warehouse-Id", warehouse.toString());
	}

	private static MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, String json) {
		return request.contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private String id(ResultActions result) throws Exception {
		return this.json.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asString();
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
