package com.geststock.warehouse;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
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

import com.geststock.support.PostgresTestConfiguration;
import com.geststock.support.WithPermissionsSecurityContextFactory;
import com.geststock.user.UserService;
import com.geststock.user.dto.CreateUserRequest;

import tools.jackson.databind.json.JsonMapper;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Périmètre de dépôts avec de vrais jetons : les affectations sont relues en base à chaque
 * requête ; l'en-tête X-Warehouse-Id du front ne peut pas l'élargir.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class WarehouseScopeIT {

	private static final String PASSWORD = "Optique2026";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private UserService users;

	@Autowired
	private JsonMapper json;

	private UUID main;

	private UUID plateau;

	private UUID cocody;

	@BeforeEach
	void createWarehouses() {
		this.main = this.jdbc.queryForObject("select id from warehouse where main", UUID.class);
		this.plateau = this.jdbc.queryForObject(
				"insert into warehouse (code, name) values ('WS-PLT', 'Scope Plateau') returning id", UUID.class);
		this.cocody = this.jdbc.queryForObject(
				"insert into warehouse (code, name) values ('WS-COC', 'Scope Cocody') returning id", UUID.class);
	}

	@AfterEach
	void cleanUp() {
		this.jdbc.update("delete from app_user where username like 'wscope\\_%'");
		this.jdbc.update("delete from warehouse where code like 'WS-%'");
	}

	@Test
	void cashierIsConfinedToAssignedWarehouses() throws Exception {
		createUser("wscope_caisse", "CAISSIER", this.plateau, List.of(this.plateau));
		String token = accessToken("wscope_caisse");

		this.mvc.perform(get("/api/warehouses").header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].id", containsInAnyOrder(this.plateau.toString())));
		this.mvc.perform(get("/api/warehouses/{id}", this.cocody).header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isNotFound());

		// Choix d'un dépôt hors périmètre : refusé, jamais remplacé en silence.
		this.mvc.perform(get("/api/products").header(HttpHeaders.AUTHORIZATION, bearer(token))
			.header("X-Warehouse-Id", this.cocody.toString()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("WAREHOUSE_ACCESS_DENIED"));
		this.mvc.perform(get("/api/products").header(HttpHeaders.AUTHORIZATION, bearer(token))
			.header("X-Warehouse-Id", this.main.toString()))
			.andExpect(status().isForbidden());

		// En-tête absent : dépôt par défaut de l'utilisateur, renvoyé par le serveur.
		this.mvc.perform(get("/api/products").header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isOk())
			.andExpect(header().string("X-Warehouse-Id", this.plateau.toString()));
	}

	@Test
	void anInvalidUnknownOrInactiveWarehouseIsRefusedNeverReplaced() throws Exception {
		createUser("wscope_strict", "CAISSIER", this.plateau, List.of(this.plateau, this.cocody));
		String token = accessToken("wscope_strict");
		this.jdbc.update("update warehouse set active = false where id = ?", this.cocody);

		expectRefused(token, "wh_1", 400, "INVALID_WAREHOUSE_ID");
		expectRefused(token, UUID.randomUUID().toString(), 400, "UNKNOWN_WAREHOUSE");
		expectRefused(token, this.cocody.toString(), 403, "WAREHOUSE_INACTIVE");
	}

	@Test
	void aUserWhoseWarehousesAreAllClosedHasNoWarehouse() throws Exception {
		createUser("wscope_closed", "CAISSIER", this.plateau, List.of(this.plateau));
		String token = accessToken("wscope_closed");
		this.jdbc.update("update warehouse set active = false where id = ?", this.plateau);

		this.mvc.perform(get("/api/warehouses/current").header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NO_WAREHOUSE_AVAILABLE"));
	}

	@Test
	void assignmentChangesApplyToExistingTokensImmediately() throws Exception {
		UUID id = createUser("wscope_move", "CAISSIER", this.plateau, List.of(this.plateau));
		String token = accessToken("wscope_move");

		this.jdbc.update("update app_user set default_warehouse_id = ? where id = ?", this.cocody, id);
		this.jdbc.update("delete from user_warehouse where user_id = ?", id);
		this.jdbc.update("insert into user_warehouse (user_id, warehouse_id) values (?, ?)", id, this.cocody);

		this.mvc.perform(get("/api/warehouses/current").header(HttpHeaders.AUTHORIZATION, bearer(token))
			.header("X-Warehouse-Id", this.plateau.toString()))
			.andExpect(status().isForbidden());
		this.mvc.perform(get("/api/warehouses/current").header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(jsonPath("$.id").value(this.cocody.toString()));
	}

	@Test
	void unassignedCashierWorksInTheMainWarehouse() throws Exception {
		createUser("wscope_mono", "CAISSIER", null, List.of());
		String token = accessToken("wscope_mono");

		this.mvc.perform(get("/api/warehouses").header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(jsonPath("$.items[*].id", containsInAnyOrder(this.main.toString())));
		this.mvc.perform(get("/api/warehouses/current").header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(jsonPath("$.id").value(this.main.toString()));
		this.mvc.perform(get("/api/warehouses/current").header(HttpHeaders.AUTHORIZATION, bearer(token))
			.header("X-Warehouse-Id", this.main.toString()))
			.andExpect(jsonPath("$.id").value(this.main.toString()));
		// Sans affectation, seul le dépôt principal est permis : un autre dépôt désigné est refusé.
		this.mvc.perform(get("/api/warehouses/current").header(HttpHeaders.AUTHORIZATION, bearer(token))
			.header("X-Warehouse-Id", this.plateau.toString()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("WAREHOUSE_ACCESS_DENIED"));
	}

	@Test
	void managerIsConfinedToAssignedWarehousesAndCannotAdministerThem() throws Exception {
		createUser("wscope_gest", "GESTIONNAIRE", this.plateau, List.of(this.plateau, this.cocody));
		String token = accessToken("wscope_gest");

		this.mvc.perform(get("/api/warehouses").header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(jsonPath("$.items[*].id", containsInAnyOrder(this.plateau.toString(), this.cocody.toString())));
		this.mvc.perform(get("/api/warehouses/current").header(HttpHeaders.AUTHORIZATION, bearer(token))
			.header("X-Warehouse-Id", this.cocody.toString()))
			.andExpect(jsonPath("$.id").value(this.cocody.toString()));
		this.mvc.perform(get("/api/products").header(HttpHeaders.AUTHORIZATION, bearer(token))
			.header("X-Warehouse-Id", this.main.toString()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("WAREHOUSE_ACCESS_DENIED"));

		// Plus de vue transversale ni d'administration des dépôts (V029).
		this.mvc.perform(get("/api/sales").header(HttpHeaders.AUTHORIZATION, bearer(token))
			.param("allWarehouses", "true"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("WAREHOUSE_ACCESS_DENIED"));
		this.mvc.perform(post("/api/warehouses").header(HttpHeaders.AUTHORIZATION, bearer(token))
			.contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"WS interdit\"}"))
			.andExpect(status().isForbidden());
	}

	@Test
	void adminWithWarehouseAllSeesAndSelectsEveryWarehouse() throws Exception {
		createUser("wscope_admin", "ADMIN", this.plateau, List.of(this.plateau));
		String token = accessToken("wscope_admin");

		this.mvc.perform(get("/api/warehouses").header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(jsonPath("$.items[*].id",
					hasItems(this.main.toString(), this.plateau.toString(), this.cocody.toString())));
		this.mvc.perform(get("/api/warehouses/current").header(HttpHeaders.AUTHORIZATION, bearer(token))
			.header("X-Warehouse-Id", this.cocody.toString()))
			.andExpect(jsonPath("$.id").value(this.cocody.toString()));
		this.mvc.perform(get("/api/sales").header(HttpHeaders.AUTHORIZATION, bearer(token))
			.param("allWarehouses", "true"))
			.andExpect(status().isOk());
	}

	@Test
	void requestsWithoutTokenAreRejectedBeforeAnyWarehouseResolution() throws Exception {
		this.mvc.perform(get("/api/warehouses/current").header("X-Warehouse-Id", this.main.toString()))
			.andExpect(status().isUnauthorized());
	}

	private void expectRefused(String token, String header, int status, String code) throws Exception {
		this.mvc.perform(get("/api/products").header(HttpHeaders.AUTHORIZATION, bearer(token))
			.header("X-Warehouse-Id", header))
			.andExpect(status().is(status))
			.andExpect(jsonPath("$.code").value(code))
			.andExpect(header().doesNotExist("X-Warehouse-Id"));
	}

	private UUID createUser(String username, String role, UUID defaultWarehouse, List<UUID> warehouses) {
		return WithPermissionsSecurityContextFactory.runWithAllPermissions(() -> this.users.create(new CreateUserRequest(
				username, null, "Test", null, null, null, PASSWORD, List.of(role), null, defaultWarehouse, warehouses))
			.id());
	}

	private String accessToken(String username) throws Exception {
		ResultActions login = this.mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\": \"" + username + "\", \"password\": \"" + PASSWORD + "\"}"))
			.andExpect(status().isOk());
		return this.json.readTree(login.andReturn().getResponse().getContentAsString()).get("accessToken").asString();
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

}
