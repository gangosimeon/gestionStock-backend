package com.geststock.audit;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.geststock.common.security.Permissions;
import com.geststock.security.CurrentUser;
import com.geststock.security.CurrentUserAuthentication;
import com.geststock.support.PostgresTestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Journal d'audit et dépôts (D9) : un lecteur sans warehouse:all ne voit, ne compte et n'exporte
 * que les entrées de ses dépôts ; les entrées sans dépôt lui restent invisibles ; il ne peut pas
 * forcer un filtre sur un autre dépôt. Les échecs enregistrent le dépôt courant.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@Transactional
class AuditScopeIT {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	private UUID plateau;

	private UUID cocody;

	private UUID readerId;

	private UUID inPlateau;

	private UUID inCocody;

	private UUID withoutWarehouse;

	@BeforeEach
	void setUp() {
		this.plateau = this.jdbc.queryForObject(
				"insert into warehouse (code, name) values ('AU-PLT', 'Audit Plateau') returning id", UUID.class);
		this.cocody = this.jdbc.queryForObject(
				"insert into warehouse (code, name) values ('AU-COC', 'Audit Cocody') returning id", UUID.class);
		this.readerId = this.jdbc.queryForObject(
				"insert into app_user (username, last_name) values ('audit_lecteur', 'Lecteur') returning id", UUID.class);
		this.inPlateau = entry("SALE", "SALE", "AUDSCOPE-VTE-PLT", this.plateau);
		this.inCocody = entry("SALE", "SALE", "AUDSCOPE-VTE-COC", this.cocody);
		this.withoutWarehouse = entry("PRODUCT", "CATALOG", "AUDSCOPE-MON-1", null);
	}

	@Test
	void aRestrictedReaderOnlySeesTheEntriesOfHisWarehouses() throws Exception {
		this.mvc.perform(get("/api/audit-logs").param("search", "audscope").with(reader()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].entityLabel").value("AUDSCOPE-VTE-PLT"));

		this.mvc.perform(get("/api/audit-logs/{id}", this.inPlateau).with(reader())).andExpect(status().isOk());
		this.mvc.perform(get("/api/audit-logs/{id}", this.inCocody).with(reader())).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/audit-logs/{id}", this.withoutWarehouse).with(reader()))
			.andExpect(status().isNotFound());
	}

	@Test
	void aReaderCannotForceAFilterOnAnotherWarehouse() throws Exception {
		this.mvc.perform(get("/api/audit-logs").param("warehouseId", this.cocody.toString()).with(reader()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("WAREHOUSE_ACCESS_DENIED"));
		this.mvc.perform(get("/api/audit-logs/export").param("warehouseId", this.cocody.toString()).with(reader()))
			.andExpect(status().isForbidden());
		this.mvc.perform(get("/api/audit-logs").param("warehouseId", this.plateau.toString()).with(reader()))
			.andExpect(jsonPath("$.total").value(1));
	}

	@Test
	void statisticsAndExportsStayWithinThePerimeter() throws Exception {
		this.mvc.perform(get("/api/audit-logs/stats").with(reader()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalToday").value(1))
			.andExpect(jsonPath("$.mostActiveUser").value("audit_lecteur"));

		this.mvc.perform(get("/api/audit-logs/export").param("search", "audscope").with(reader()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("AUDSCOPE-VTE-PLT")))
			.andExpect(content().string(not(containsString("AUDSCOPE-VTE-COC"))))
			.andExpect(content().string(not(containsString("AUDSCOPE-MON-1"))));
	}

	@Test
	void aReaderWhoSeesEveryWarehouseGetsTheWholeJournal() throws Exception {
		this.mvc.perform(get("/api/audit-logs").param("search", "audscope").with(admin()))
			.andExpect(jsonPath("$.total").value(3));
		this.mvc.perform(get("/api/audit-logs").param("warehouseId", this.cocody.toString()).with(admin()))
			.andExpect(jsonPath("$.items[*].entityLabel", org.hamcrest.Matchers.hasItem("AUDSCOPE-VTE-COC")));
	}

	@Test
	void aFailureRecordsTheCurrentWarehouse() throws Exception {
		UUID main = this.jdbc.queryForObject("select id from warehouse where main", UUID.class);
		String unknownSale = UUID.randomUUID().toString();
		this.mvc.perform(post("/api/sales/{id}/cancel", unknownSale).with(admin()).header("X-Warehouse-Id", main.toString())
			.contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"Erreur\"}"))
			.andExpect(status().isNotFound());

		assertThat(this.jdbc.queryForObject(
				"select warehouse_id from audit_log where status = 'FAILURE' and entity_id = ?", UUID.class, unknownSale))
			.isEqualTo(main);
	}

	// --- Outils ---------------------------------------------------------------------------------

	private UUID entry(String entityType, String module, String label, UUID warehouse) {
		return this.jdbc.queryForObject("""
				insert into audit_log (action, entity_type, entity_label, status, module, user_id, username, warehouse_id)
				values ('CREATE', ?, ?, 'SUCCESS', ?, ?, 'audit_lecteur', ?) returning id""", UUID.class, entityType, label,
				module, this.readerId, warehouse);
	}

	/** Lecteur du journal affecté au Plateau seulement (sans warehouse:all). */
	private RequestPostProcessor reader() {
		return as(this.readerId, "audit_lecteur", this.plateau, Permissions.AUDIT_READ);
	}

	private RequestPostProcessor admin() {
		return as(this.readerId, "audit_admin", null, Permissions.ALL.toArray(String[]::new));
	}

	private static RequestPostProcessor as(UUID id, String username, UUID warehouse, String... permissions) {
		CurrentUser user = new CurrentUser(id, username, Set.of(), Set.of(permissions), warehouse,
				warehouse != null ? Set.of(warehouse) : Set.of());
		Jwt token = Jwt.withTokenValue("test").header("alg", "HS256").subject(id.toString()).build();
		Collection<SimpleGrantedAuthority> authorities = java.util.Arrays.stream(permissions)
			.map(SimpleGrantedAuthority::new)
			.toList();
		return authentication(new CurrentUserAuthentication(user, token, authorities));
	}

}
