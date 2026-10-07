package com.geststock.user;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.geststock.support.PostgresTestConfiguration;
import com.geststock.support.WithPermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Module utilisateur de bout en bout (HTTP → service → JPA → PostgreSQL). Chaque test est
 * annulé à la fin (transaction de test partagée avec MockMvc).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@Transactional
@WithPermissions(all = true)
class UserApiIT {

	private static final String PASSWORD = "Optique2026";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Test
	void createsUserWithHashedPasswordOnly() throws Exception {
		String id = create("""
				{"username": "pierre.d", "firstName": "Pierre", "lastName": "Diallo",
				 "email": "pierre.d@optivision.ci", "password": "%s", "roles": ["CAISSIER"]}
				""".formatted(PASSWORD));

		String hash = this.jdbc.queryForObject("select password_hash from app_user where id = ?::uuid", String.class, id);
		assertThat(hash).startsWith("{bcrypt}$2a$12$").doesNotContain(PASSWORD);
		assertThat(this.passwordEncoder.matches(PASSWORD, hash)).isTrue();

		this.mvc.perform(get("/api/users/{id}", id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.fullName").value("Pierre Diallo"))
			.andExpect(jsonPath("$.hasPassword").value(true))
			.andExpect(jsonPath("$.mustChangePassword").value(true))
			.andExpect(jsonPath("$.isActive").value(true))
			.andExpect(jsonPath("$.createdAt").isString())
			.andExpect(jsonPath("$.passwordHash").doesNotExist());
	}

	@Test
	void acceptsTheCurrentFrontPayload() throws Exception {
		String id = create("""
				{"username": "fatou.s", "fullName": "Fatou Sow", "phone": "+225 07 00 00 04",
				 "roles": ["CAISSIER", "GESTIONNAIRE"], "isActive": true}
				""");

		this.mvc.perform(put("/api/users/{id}", id).contentType(MediaType.APPLICATION_JSON).content("""
				{"username": "fatou.sow", "fullName": "Fatou Sow Koné", "phone": "+225 07 00 00 05", "roles": ["CAISSIER"]}
				"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.username").value("fatou.sow"))
			.andExpect(jsonPath("$.firstName").value("Fatou"))
			.andExpect(jsonPath("$.lastName").value("Sow Koné"))
			.andExpect(jsonPath("$.roles", hasSize(1)))
			.andExpect(jsonPath("$.hasPassword").value(false));
	}

	@Test
	void rejectsDuplicateUsernameAndEmailIgnoringCase() throws Exception {
		create("""
				{"username": "marie.k", "lastName": "Koné", "email": "marie@optivision.ci", "roles": ["GESTIONNAIRE"]}
				""");

		this.mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content("""
				{"username": "MARIE.K", "lastName": "Autre", "roles": ["CAISSIER"]}
				"""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("USERNAME_ALREADY_USED"));

		this.mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content("""
				{"username": "marie.b", "lastName": "Autre", "email": "MARIE@optivision.ci", "roles": ["CAISSIER"]}
				"""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("EMAIL_ALREADY_USED"));
	}

	@Test
	void deleteDisablesTheAccountWithoutRemovingIt() throws Exception {
		String id = create("""
				{"username": "temp.user", "lastName": "Temporaire", "roles": ["CAISSIER"]}
				""");

		this.mvc.perform(delete("/api/users/{id}", id)).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));

		Boolean active = this.jdbc.queryForObject("select active from app_user where id = ?::uuid", Boolean.class, id);
		assertThat(active).as("ligne conservée, compte désactivé").isFalse();

		this.mvc.perform(patch("/api/users/{id}/active", id).contentType(MediaType.APPLICATION_JSON)
			.content("{\"isActive\": true}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.isActive").value(true));
	}

	@Test
	void keepsAtLeastOneActiveAdministrator() throws Exception {
		String admin = create("""
				{"username": "seul.admin", "lastName": "Admin", "roles": ["ADMIN"]}
				""");

		this.mvc.perform(delete("/api/users/{id}", admin))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("LAST_ADMIN_REQUIRED"));

		create("""
				{"username": "second.admin", "lastName": "Admin", "roles": ["ADMIN"]}
				""");
		this.mvc.perform(delete("/api/users/{id}", admin)).andExpect(status().isOk());
	}

	@Test
	void rejectsUnknownRoleAndWeakPassword() throws Exception {
		this.mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content("""
				{"username": "x.user", "lastName": "X", "roles": ["SUPERVISEUR"]}
				"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("UNKNOWN_ROLE"))
			.andExpect(jsonPath("$.message").value("Rôle inconnu : SUPERVISEUR."));

		String id = create("""
				{"username": "y.user", "lastName": "Y", "roles": ["CAISSIER"]}
				""");
		this.mvc.perform(put("/api/users/{id}/password", id).contentType(MediaType.APPLICATION_JSON)
			.content("{\"password\": \"azerty\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PASSWORD_POLICY_VIOLATION"));
	}

	@Test
	void assignsWarehousesWithDefaultIncluded() throws Exception {
		String mainWarehouse = this.jdbc.queryForObject("select id::text from warehouse where code = 'MAG-01'", String.class);

		this.mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content("""
				{"username": "vendeur.1", "lastName": "Vendeur", "roles": ["CAISSIER"], "defaultWarehouseId": "%s"}
				""".formatted(mainWarehouse)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.defaultWarehouseId").value(mainWarehouse))
			.andExpect(jsonPath("$.warehouseIds[0]").value(mainWarehouse));

		this.mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content("""
				{"username": "vendeur.2", "lastName": "Vendeur", "roles": ["CAISSIER"], "warehouseIds": ["%s"]}
				""".formatted(UUID.randomUUID())))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("UNKNOWN_WAREHOUSE"));
	}

	@Test
	void searchesPaginatesAndSorts() throws Exception {
		create("""
				{"username": "zoe.b", "firstName": "Zoé", "lastName": "Bamba", "roles": ["CAISSIER"]}
				""");
		create("""
				{"username": "awa.t", "firstName": "Awa", "lastName": "Traoré", "roles": ["GESTIONNAIRE"]}
				""");

		this.mvc.perform(get("/api/users").param("q", "bamb"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].username").value("zoe.b"));

		this.mvc.perform(get("/api/users").param("role", "gestionnaire").param("page", "0").param("size", "10"))
			.andExpect(jsonPath("$.items[0].username").value("awa.t"))
			.andExpect(jsonPath("$.size").value(10));

		this.mvc.perform(get("/api/users").param("q", ".").param("sort", "username,desc"))
			.andExpect(jsonPath("$.items[0].username").value("zoe.b"));
	}

	private String create(String json) throws Exception {
		MvcResult result = this.mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(json))
			.andExpect(status().isCreated())
			.andReturn();
		String body = result.getResponse().getContentAsString();
		int start = body.indexOf("\"id\":\"") + 6;
		return body.substring(start, body.indexOf('"', start));
	}

}
