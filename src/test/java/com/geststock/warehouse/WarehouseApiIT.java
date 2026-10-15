package com.geststock.warehouse;

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
import org.springframework.transaction.annotation.Transactional;

import com.geststock.support.PostgresTestConfiguration;
import com.geststock.support.WithPermissions;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Dépôts de bout en bout sur PostgreSQL : contrat front, règles, dépôt principal, droits. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@Transactional
@WithPermissions(all = true)
class WarehouseApiIT {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private JsonMapper json;

	@Test
	void listMatchesTheFrontContract() throws Exception {
		this.mvc.perform(get("/api/warehouses"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].name", hasItem("Magasin principal")))
			.andExpect(jsonPath("$.items[0].id").isString())
			.andExpect(jsonPath("$.items[0].name").isString())
			.andExpect(jsonPath("$.total").isNumber());
	}

	@Test
	void frontCreatesWithANameOnlyAndGetsAGeneratedCode() throws Exception {
		// Charge utile exacte de la page « Dépôts » du front.
		create("{\"name\": \"Magasin Cocody\"}")
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", matchesPattern("/api/warehouses/[0-9a-f-]{36}")))
			.andExpect(jsonPath("$.id").isString())
			.andExpect(jsonPath("$.name").value("Magasin Cocody"))
			.andExpect(jsonPath("$.code", matchesPattern("MAG-\\d{2,}")))
			.andExpect(jsonPath("$.type").value("STORE"))
			.andExpect(jsonPath("$.main").value(false))
			.andExpect(jsonPath("$.active").value(true));

		create("{\"name\": \"Entrepôt Yopougon\", \"type\": \"WAREHOUSE\"}")
			.andExpect(jsonPath("$.code", matchesPattern("DEP-\\d{2,}")));
	}

	@Test
	void createsAndUpdatesAFullWarehouse() throws Exception {
		String id = idOf(create("""
				{"code": "mag-plt", "name": "Magasin Plateau", "type": "STORE",
				 "address": "Avenue Chardy, Abidjan", "phone": "+225 27 20 00 00"}
				""").andExpect(jsonPath("$.code").value("MAG-PLT")));

		this.mvc.perform(put("/api/warehouses/{id}", id).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"Magasin Plateau Centre\", \"address\": \"Rue du Commerce\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value("MAG-PLT"))
			.andExpect(jsonPath("$.type").value("STORE"))
			.andExpect(jsonPath("$.name").value("Magasin Plateau Centre"))
			.andExpect(jsonPath("$.address").value("Rue du Commerce"))
			.andExpect(jsonPath("$.phone").doesNotExist());

		this.mvc.perform(get("/api/warehouses/{id}", id)).andExpect(jsonPath("$.name").value("Magasin Plateau Centre"));
	}

	@Test
	void enforcesUniqueCodeAndNameIgnoringCase() throws Exception {
		create("{\"code\": \"MAG-UNQ\", \"name\": \"Magasin unique\"}").andExpect(status().isCreated());

		create("{\"code\": \"mag-unq\", \"name\": \"Autre\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("WAREHOUSE_CODE_ALREADY_USED"));
		create("{\"name\": \"MAGASIN PRINCIPAL\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("WAREHOUSE_NAME_ALREADY_USED"));
	}

	@Test
	void validatesServerSide() throws Exception {
		create("{\"code\": \"bad code!\", \"name\": \" \", \"type\": \"STORE\", \"phone\": \"abc\"}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[*].field", hasItem("code")))
			.andExpect(jsonPath("$.errors[*].field", hasItem("name")))
			.andExpect(jsonPath("$.errors[*].field", hasItem("phone")));
		create("{\"name\": \"Type inconnu\", \"type\": \"SHOP\"}").andExpect(status().isBadRequest());
		this.mvc.perform(get("/api/warehouses/{id}", "wh_1")).andExpect(status().isBadRequest());
		this.mvc.perform(get("/api/warehouses/{id}", "0192a000-0000-7000-8000-000000000000"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("WAREHOUSE_NOT_FOUND"));
	}

	@Test
	void deleteDeactivatesKeepsTheRowAndCanBeUndone() throws Exception {
		String id = idOf(create("{\"name\": \"Magasin éphémère\"}"));

		this.mvc.perform(delete("/api/warehouses/{id}", id)).andExpect(jsonPath("$.ok").value(true));
		assertThat(this.jdbc.queryForObject("select active from warehouse where id = ?::uuid", Boolean.class, id))
			.as("ligne conservée").isFalse();
		this.mvc.perform(get("/api/warehouses")).andExpect(jsonPath("$.items[*].id", not(hasItem(id))));
		this.mvc.perform(get("/api/warehouses").param("status", "INACTIVE"))
			.andExpect(jsonPath("$.items[*].id", hasItem(id)));

		this.mvc.perform(patch("/api/warehouses/{id}/active", id).contentType(MediaType.APPLICATION_JSON)
			.content("{\"active\": true}"))
			.andExpect(jsonPath("$.active").value(true));
	}

	@Test
	void mainWarehouseIsUniqueAndCannotBeDeactivated() throws Exception {
		String main = this.jdbc.queryForObject("select id::text from warehouse where main", String.class);
		assertThat(main).isEqualTo(this.jdbc.queryForObject("select id::text from warehouse where code = 'MAG-01'",
				String.class));

		this.mvc.perform(delete("/api/warehouses/{id}", main))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("MAIN_WAREHOUSE_REQUIRED"));

		String other = idOf(create("{\"name\": \"Nouveau siège\"}"));
		this.mvc.perform(post("/api/warehouses/{id}/main", other))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.main").value(true));
		assertThat(this.jdbc.queryForList("select id::text from warehouse where main", String.class))
			.containsExactly(other);

		this.mvc.perform(delete("/api/warehouses/{id}", main)).andExpect(status().isOk());
		this.mvc.perform(post("/api/warehouses/{id}/main", main))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("MAIN_WAREHOUSE_INACTIVE"));
	}

	@Test
	void searchesFiltersAndPaginates() throws Exception {
		create("{\"code\": \"ZZ-A\", \"name\": \"Zz Alpha\"}");
		create("{\"code\": \"ZZ-B\", \"name\": \"Zz Beta\", \"type\": \"WAREHOUSE\"}");
		create("{\"code\": \"ZZ-C\", \"name\": \"Zz Gamma\", \"type\": \"WAREHOUSE\"}");

		this.mvc.perform(get("/api/warehouses").param("q", "zz-").param("type", "WAREHOUSE"))
			.andExpect(jsonPath("$.total").value(2));
		this.mvc.perform(get("/api/warehouses").param("q", "zz").param("sort", "name,desc").param("page", "0")
			.param("size", "2"))
			.andExpect(jsonPath("$.items[0].name").value("Zz Gamma"))
			.andExpect(jsonPath("$.total").value(3))
			.andExpect(jsonPath("$.totalPages").value(2));
		this.mvc.perform(get("/api/warehouses").param("sort", "address,asc"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("UNKNOWN_SORT_FIELD"));
	}

	@Test
	void currentWarehouseIsResolvedAndEchoedByTheServer() throws Exception {
		String main = this.jdbc.queryForObject("select id::text from warehouse where main", String.class);
		String other = idOf(create("{\"name\": \"Magasin courant\"}"));

		this.mvc.perform(get("/api/warehouses/current").header("X-Warehouse-Id", other))
			.andExpect(jsonPath("$.id").value(other))
			.andExpect(header().string("X-Warehouse-Id", other));
		// Sans en-tête : dépôt par défaut (ici le principal), renvoyé par le serveur.
		this.mvc.perform(get("/api/warehouses/current"))
			.andExpect(jsonPath("$.id").value(main))
			.andExpect(header().string("X-Warehouse-Id", main));
		// Identifiant de l'ancien mock (localStorage du front) : refusé, plus de repli silencieux.
		this.mvc.perform(get("/api/products").header("X-Warehouse-Id", "wh_1"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_WAREHOUSE_ID"));
	}

	@Test
	@WithPermissions({ "warehouse:read" })
	void readersSeeOnlyActiveWarehousesOfTheirScopeAndCannotManage() throws Exception {
		String main = this.jdbc.queryForObject("select id::text from warehouse where main", String.class);
		String other = this.jdbc.queryForObject("""
				insert into warehouse (code, name) values ('ZZ-HIDDEN', 'Magasin caché') returning id::text""",
				String.class);

		// Utilisateur sans affectation : périmètre = dépôt principal.
		this.mvc.perform(get("/api/warehouses"))
			.andExpect(jsonPath("$.items[*].id", hasItem(main)))
			.andExpect(jsonPath("$.items[*].id", not(hasItem(other))));
		this.mvc.perform(get("/api/warehouses").param("status", "ALL"))
			.andExpect(jsonPath("$.items[*].id", not(hasItem(other))));
		this.mvc.perform(get("/api/warehouses/{id}", other)).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/warehouses/current").header("X-Warehouse-Id", other))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("WAREHOUSE_ACCESS_DENIED"));

		create("{\"name\": \"Interdit\"}").andExpect(status().isForbidden());
		this.mvc.perform(delete("/api/warehouses/{id}", other)).andExpect(status().isForbidden());
		this.mvc.perform(post("/api/warehouses/{id}/main", other)).andExpect(status().isForbidden());
	}

	@Test
	@WithPermissions({ "warehouse:read", "warehouse:manage" })
	void manageWithoutWarehouseAllNeverWidensThePerimeter() throws Exception {
		String main = this.jdbc.queryForObject("select id::text from warehouse where main", String.class);
		String other = this.jdbc.queryForObject("""
				insert into warehouse (code, name) values ('ZZ-OUT', 'Magasin hors périmètre') returning id::text""",
				String.class);

		// Utilisateur sans affectation : périmètre = dépôt principal, même avec warehouse:manage.
		this.mvc.perform(get("/api/warehouses").param("status", "ALL"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].id", hasItem(main)))
			.andExpect(jsonPath("$.items[*].id", not(hasItem(other))));
		this.mvc.perform(get("/api/warehouses/{id}", other)).andExpect(status().isNotFound());

		// Administration d'un dépôt invisible : 404, jamais modifié à l'aveugle.
		this.mvc.perform(put("/api/warehouses/{id}", other).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"Renommé\"}")).andExpect(status().isNotFound());
		this.mvc.perform(patch("/api/warehouses/{id}/active", other).contentType(MediaType.APPLICATION_JSON)
			.content("{\"active\": false}")).andExpect(status().isNotFound());
		this.mvc.perform(delete("/api/warehouses/{id}", other)).andExpect(status().isNotFound());
		assertThat(this.jdbc.queryForObject("select name || '/' || active from warehouse where id = ?::uuid",
				String.class, other)).isEqualTo("Magasin hors périmètre/true");

		// Le dépôt principal vaut pour toute l'entreprise : réservé à warehouse:all.
		this.mvc.perform(post("/api/warehouses/{id}/main", main))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("WAREHOUSE_ACCESS_DENIED"));
	}

	@Test
	@WithPermissions({ "product:read" })
	void listingWarehousesRequiresThePermission() throws Exception {
		this.mvc.perform(get("/api/warehouses")).andExpect(status().isForbidden());
	}

	private ResultActions create(String body) throws Exception {
		return this.mvc.perform(post("/api/warehouses").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private String idOf(ResultActions result) throws Exception {
		return this.json.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asString();
	}

}
