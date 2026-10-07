package com.geststock.role;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.geststock.support.PostgresTestConfiguration;
import com.geststock.support.WithPermissions;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** API des rôles et permissions, de bout en bout sur PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@Transactional
class RoleApiIT {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	@WithPermissions(all = true)
	void listsBuiltInRolesWithEffectivePermissions() throws Exception {
		this.mvc.perform(get("/api/roles"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.code == 'ADMIN')].permissions[*]", hasSize(45)))
			.andExpect(jsonPath("$.items[?(@.code == 'ADMIN')].editable").value(false))
			.andExpect(jsonPath("$.items[?(@.code == 'CAISSIER')].builtIn").value(true))
			.andExpect(jsonPath("$.items[?(@.code == 'CAISSIER')].permissions[*]", hasItem("sale:create")));

		this.mvc.perform(get("/api/permissions"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(45))
			.andExpect(jsonPath("$.items[?(@.code == 'product:create')].domain").value("product"));
	}

	@Test
	@WithPermissions(all = true)
	void managesACustomRoleLifecycle() throws Exception {
		String body = this.mvc.perform(post("/api/roles").contentType(MediaType.APPLICATION_JSON).content("""
				{"code": "VENDEUR_SENIOR", "label": "Vendeur senior",
				 "permissions": ["sale:create", "sale:cancel", "customer:read"]}
				"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.builtIn").value(false))
			.andExpect(jsonPath("$.editable").value(true))
			.andExpect(jsonPath("$.permissions", hasSize(3)))
			.andReturn().getResponse().getContentAsString();
		String id = body.substring(body.indexOf("\"id\":\"") + 6, body.indexOf("\"", body.indexOf("\"id\":\"") + 6));

		this.mvc.perform(put("/api/roles/{id}", id).contentType(MediaType.APPLICATION_JSON).content("""
				{"label": "Vendeur confirmé", "permissions": ["sale:create", "customer:read", "customer:create"]}
				"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.label").value("Vendeur confirmé"))
			.andExpect(jsonPath("$.permissions[0]").value("customer:create"));

		this.mvc.perform(delete("/api/roles/{id}", id)).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
		this.mvc.perform(get("/api/roles/{id}", id)).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("ROLE_NOT_FOUND"));
	}

	@Test
	@WithPermissions(all = true)
	void protectsSystemRoles() throws Exception {
		String adminId = this.jdbc.queryForObject("select id::text from role where code = 'ADMIN'", String.class);
		String cashierId = this.jdbc.queryForObject("select id::text from role where code = 'CAISSIER'", String.class);

		this.mvc.perform(put("/api/roles/{id}", adminId).contentType(MediaType.APPLICATION_JSON)
			.content("{\"label\": \"Admin\", \"permissions\": []}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ROLE_NOT_EDITABLE"));

		this.mvc.perform(delete("/api/roles/{id}", cashierId))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ROLE_NOT_DELETABLE"));
	}

	@Test
	@WithPermissions(all = true)
	void refusesToDeleteARoleStillAssigned() throws Exception {
		this.mvc.perform(post("/api/roles").contentType(MediaType.APPLICATION_JSON)
			.content("{\"code\": \"MAGASINIER\", \"label\": \"Magasinier\", \"permissions\": [\"stock:read\"]}"))
			.andExpect(status().isCreated());
		this.mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\": \"mag.1\", \"lastName\": \"Magasinier\", \"roles\": [\"MAGASINIER\"]}"))
			.andExpect(status().isCreated());
		String roleId = this.jdbc.queryForObject("select id::text from role where code = 'MAGASINIER'", String.class);

		this.mvc.perform(get("/api/roles/{id}", roleId)).andExpect(jsonPath("$.userCount").value(1));
		this.mvc.perform(delete("/api/roles/{id}", roleId))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ROLE_IN_USE"));
	}

	@Test
	@WithPermissions(all = true)
	void validatesCodesAndPermissions() throws Exception {
		this.mvc.perform(post("/api/roles").contentType(MediaType.APPLICATION_JSON)
			.content("{\"code\": \"CAISSIER\", \"label\": \"Doublon\", \"permissions\": []}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ROLE_CODE_ALREADY_USED"));

		this.mvc.perform(post("/api/roles").contentType(MediaType.APPLICATION_JSON)
			.content("{\"code\": \"TEST_ROLE\", \"label\": \"Test\", \"permissions\": [\"product:fly\"]}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("UNKNOWN_PERMISSION"))
			.andExpect(jsonPath("$.message").value("Permission inconnue : product:fly."));

		this.mvc.perform(post("/api/roles").contentType(MediaType.APPLICATION_JSON)
			.content("{\"code\": \"bad code\", \"label\": \"Test\", \"permissions\": []}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	@WithPermissions({ "role:manage", "product:read" })
	void cannotGrantAPermissionYouDoNotHold() throws Exception {
		this.mvc.perform(post("/api/roles").contentType(MediaType.APPLICATION_JSON)
			.content("{\"code\": \"PIRATE\", \"label\": \"Pirate\", \"permissions\": [\"product:read\", \"user:manage\"]}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("PRIVILEGE_ESCALATION"))
			.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("user:manage")));

		this.mvc.perform(post("/api/roles").contentType(MediaType.APPLICATION_JSON)
			.content("{\"code\": \"LECTEUR\", \"label\": \"Lecteur\", \"permissions\": [\"product:read\"]}"))
			.andExpect(status().isCreated());
	}

	@Test
	@WithPermissions({ "user:manage" })
	void userManagerCannotAssignARoleMorePowerfulThanHimself() throws Exception {
		this.mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\": \"nouvel.admin\", \"lastName\": \"Admin\", \"roles\": [\"ADMIN\"]}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("PRIVILEGE_ESCALATION"));
	}

	@Test
	@WithPermissions({ "sale:create" })
	void roleManagementRequiresRoleManagePermission() throws Exception {
		this.mvc.perform(get("/api/roles")).andExpect(status().isForbidden());
		this.mvc.perform(post("/api/roles").contentType(MediaType.APPLICATION_JSON)
			.content("{\"code\": \"X_ROLE\", \"label\": \"X\", \"permissions\": []}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
	}

}
