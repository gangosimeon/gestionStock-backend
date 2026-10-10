package com.geststock.audit;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.geststock.common.security.Permissions;
import com.geststock.security.CurrentUser;
import com.geststock.security.CurrentUserAuthentication;
import com.geststock.support.PostgresTestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Journal d'audit de bout en bout : identité relevée côté serveur (jamais celle envoyée par
 * le client), module, valeurs avant/après, échecs tracés avec leur code d'erreur, opérations
 * sensibles (utilisateurs, rôles, produits, stock, crédit client), consultation, statistiques
 * et export. Les réussites suivent la transaction du test (annulée) ; les échecs sont écrits
 * dans leur propre transaction et restent : ils sont retrouvés par l'utilisateur du test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@Transactional
class AuditApiIT {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private JsonMapper json;

	private UUID adminId;

	private String adminName;

	private UUID category;

	@BeforeEach
	void setUp() {
		this.adminName = "audit." + UUID.randomUUID().toString().substring(0, 8);
		this.adminId = this.jdbc.queryForObject(
				"insert into app_user (username, last_name) values (?, 'Auditeur') returning id", UUID.class,
				this.adminName);
		this.category = this.jdbc.queryForObject("select id from category where name = 'Montures'", UUID.class);
	}

	// --- Identité ------------------------------------------------------------------------------------

	@Test
	void theAuthorComesFromTheSecurityContextNeverFromTheRequest() throws Exception {
		UUID forged = UUID.randomUUID();
		String id = idOf(this.mvc.perform(post("/api/products").with(admin())
			.header("X-User-Id", forged.toString())
			.header("X-Forwarded-For", "6.6.6.6")
			.header("User-Agent", "Navigateur-Test")
			.contentType(MediaType.APPLICATION_JSON)
			.content(product("AUD-MON-1", 35000).replace("{", """
					{"userId": "%s", "username": "pirate", "createdBy": "%s", """.formatted(forged, forged))))
			.andExpect(status().isCreated()));

		Map<String, Object> entry = this.jdbc.queryForMap("""
				select user_id, username, user_role, module, action, status, entity_label, ip_address, user_agent,
				       after_state::text as after_state
				from audit_log where entity_type = 'PRODUCT' and entity_id = ?""", id);
		assertThat(entry.get("user_id")).isEqualTo(this.adminId);
		assertThat(entry.get("username")).isEqualTo(this.adminName);
		assertThat(entry.get("user_role")).isEqualTo("ADMIN");
		assertThat(entry.get("module")).isEqualTo("CATALOG");
		assertThat(entry.get("action")).isEqualTo("CREATE");
		assertThat(entry.get("status")).isEqualTo("SUCCESS");
		assertThat(entry.get("entity_label")).isEqualTo("AUD-MON-1 · Produit AUD-MON-1");
		assertThat(entry.get("ip_address")).as("adresse de la connexion, pas X-Forwarded-For").isEqualTo("127.0.0.1");
		assertThat(entry.get("user_agent")).isEqualTo("Navigateur-Test");
		assertThat((String) entry.get("after_state")).doesNotContain(forged.toString()).doesNotContain("pirate");

		// Le journal ne s'écrit pas par l'API.
		this.mvc.perform(post("/api/audit-logs").with(admin()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"action\": \"LOGIN\", \"entityType\": \"USER\", \"userId\": \"%s\"}".formatted(forged)))
			.andExpect(status().isMethodNotAllowed());
		String auditId = this.jdbc.queryForObject("select id::text from audit_log where entity_id = ?", String.class, id);
		this.mvc.perform(delete("/api/audit-logs/{id}", auditId).with(admin())).andExpect(status().isMethodNotAllowed());
	}

	// --- Valeurs avant / après ------------------------------------------------------------------------

	@Test
	void anUpdateKeepsTheOldAndNewValues() throws Exception {
		String id = idOf(this.mvc.perform(post("/api/products").with(admin()).contentType(MediaType.APPLICATION_JSON)
			.content(product("AUD-MON-2", 35000))));
		this.mvc.perform(put("/api/products/{id}", id).with(admin()).contentType(MediaType.APPLICATION_JSON)
			.content(product("AUD-MON-2", 38000)))
			.andExpect(status().isOk());

		String auditId = this.jdbc.queryForObject(
				"select id::text from audit_log where entity_id = ? and action = 'UPDATE'", String.class, id);
		this.mvc.perform(get("/api/audit-logs/{id}", auditId).with(admin()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.module").value("CATALOG"))
			.andExpect(jsonPath("$.entityType").value("PRODUCT"))
			.andExpect(jsonPath("$.changes.length()").value(1))
			.andExpect(jsonPath("$.changes[0].field").value("retailPrice"))
			.andExpect(jsonPath("$.changes[0].oldValue").value(35000))
			.andExpect(jsonPath("$.changes[0].newValue").value(38000))
			.andExpect(jsonPath("$.before.retailPrice").value(35000))
			.andExpect(jsonPath("$.after.retailPrice").value(38000))
			.andExpect(jsonPath("$.username").value(this.adminName));
	}

	// --- Échecs ---------------------------------------------------------------------------------------

	@Test
	void refusedOperationsAreTracedWithTheirErrorCode() throws Exception {
		this.mvc.perform(post("/api/products").with(admin()).contentType(MediaType.APPLICATION_JSON)
			.content(product("AUD-MON-3", 1000)))
			.andExpect(status().isCreated());
		this.mvc.perform(post("/api/products").with(admin()).contentType(MediaType.APPLICATION_JSON)
			.content(product("AUD-MON-3", 1000)))
			.andExpect(status().isConflict());
		this.mvc.perform(post("/api/products").with(admin()).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest());

		UUID cashierId = this.jdbc.queryForObject(
				"insert into app_user (username, last_name) values (?, 'Caisse') returning id", UUID.class,
				"caisse." + UUID.randomUUID().toString().substring(0, 8));
		RequestPostProcessor cashier = as(cashierId, "caisse", Set.of("CAISSIER"), Permissions.SALE_CREATE,
				Permissions.PRODUCT_READ);
		this.mvc.perform(post("/api/products").with(cashier).contentType(MediaType.APPLICATION_JSON)
			.content(product("AUD-MON-4", 1000)))
			.andExpect(status().isForbidden());

		List<Map<String, Object>> failures = this.jdbc.queryForList("""
				select user_id, action, module, entity_type, meta ->> 'errorCode' as code, meta ->> 'httpStatus' as http,
				       details
				from audit_log where status = 'FAILURE' and user_id in (?, ?) order by created_at""", this.adminId,
				cashierId);
		assertThat(failures).extracting(f -> f.get("code"))
			.containsExactly("SKU_ALREADY_USED", "VALIDATION_FAILED", "ACCESS_DENIED");
		assertThat(failures).extracting(f -> f.get("http")).containsExactly("409", "400", "403");
		assertThat(failures).allSatisfy(f -> {
			assertThat(f.get("action")).isEqualTo("CREATE");
			assertThat(f.get("entity_type")).isEqualTo("PRODUCT");
			assertThat(f.get("module")).isEqualTo("CATALOG");
		});
		assertThat(failures.get(2).get("user_id")).as("l'auteur du refus est l'utilisateur connecté").isEqualTo(cashierId);
	}

	// --- Opérations sensibles -------------------------------------------------------------------------

	@Test
	void sensitiveSecurityOperationsAreTracedWithoutSecrets() throws Exception {
		String password = "Optique2026!";
		String user = idOf(this.mvc.perform(post("/api/users").with(admin()).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"username": "aud.%s", "lastName": "Kouamé", "password": "%s", "roles": ["CAISSIER"]}"""
				.formatted(UUID.randomUUID().toString().substring(0, 6), password))));
		this.mvc.perform(put("/api/users/{id}/password", user).with(admin()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"password\": \"Nouveau2026!\", \"temporary\": true}"))
			.andExpect(status().is2xxSuccessful());
		this.mvc.perform(put("/api/users/{id}/roles", user).with(admin()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"roles\": [\"GESTIONNAIRE\"]}"))
			.andExpect(status().isOk());

		List<Map<String, Object>> entries = this.jdbc.queryForList("""
				select action, module, coalesce(before_state::text, '') || coalesce(after_state::text, '')
				       || coalesce(changes::text, '') || coalesce(details, '') as content
				from audit_log where entity_type = 'USER' and entity_id = ? order by created_at""", user);
		assertThat(entries).extracting(e -> e.get("action")).containsExactly("CREATE", "PASSWORD_RESET", "ASSIGN_ROLES");
		assertThat(entries).allSatisfy(e -> {
			assertThat(e.get("module")).isEqualTo("USER");
			assertThat((String) e.get("content")).doesNotContain(password).doesNotContain("Nouveau2026!")
				.doesNotContain("$2a$");
		});
		assertThat((String) entries.get(2).get("content")).contains("GESTIONNAIRE").contains("CAISSIER");

		// Permissions d'un rôle : différence et listes ajoutées / retirées.
		String role = this.jdbc.queryForObject("select id::text from role where code = 'CAISSIER'", String.class);
		List<String> permissions = this.jdbc.queryForList("""
				select p.code from role_permission rp join permission p on p.id = rp.permission_id join role r on r.id = rp.role_id
				where r.code = 'CAISSIER' and p.code <> 'customer:pay'""", String.class);
		this.mvc.perform(put("/api/roles/{id}", role).with(admin()).contentType(MediaType.APPLICATION_JSON)
			.content(this.json.writeValueAsString(Map.of("label", "Caissier", "permissions",
					concat(permissions, "stock:read")))))
			.andExpect(status().isOk());
		Map<String, Object> roleEntry = this.jdbc.queryForMap("""
				select module, meta -> 'removedPermissions' ->> 0 as removed, changes::text as changes
				from audit_log where entity_type = 'ROLE' and entity_id = ? and status = 'SUCCESS'""", role);
		assertThat(roleEntry.get("module")).isEqualTo("SECURITY");
		assertThat(roleEntry.get("removed")).isEqualTo("customer:pay");
		assertThat((String) roleEntry.get("changes")).contains("\"field\": \"permissions\"");
	}

	@Test
	void stockAndCustomerCreditChangesAreTraced() throws Exception {
		String product = idOf(this.mvc.perform(post("/api/products").with(admin()).contentType(MediaType.APPLICATION_JSON)
			.content(product("AUD-STK-1", 1000))));
		this.mvc.perform(post("/api/stock/movements").with(admin()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"productId\": \"%s\", \"quantity\": 5, \"reason\": \"SUPPLY\", \"note\": \"Arrivage\"}"
				.formatted(product)))
			.andExpect(status().isCreated());
		Map<String, Object> stock = this.jdbc.queryForMap("""
				select module, action, before_state ->> 'quantity' as before, after_state ->> 'quantity' as after,
				       warehouse_id is not null as has_warehouse, details
				from audit_log where entity_type = 'STOCK_MOVEMENT' and meta ->> 'productId' = ?""", product);
		assertThat(stock).containsEntry("module", "STOCK").containsEntry("action", "CREATE")
			.containsEntry("before", "0").containsEntry("after", "5").containsEntry("has_warehouse", true);
		assertThat((String) stock.get("details")).contains("+5").contains("Arrivage");

		String customer = idOf(this.mvc.perform(post("/api/customers").with(admin()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"Clinique Audit\", \"creditLimit\": 50000}")));
		this.mvc.perform(put("/api/customers/{id}", customer).with(admin()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"Clinique Audit\", \"creditLimit\": 150000}"))
			.andExpect(status().isOk());
		Map<String, Object> credit = this.jdbc.queryForMap("""
				select changes::text as changes, details from audit_log
				where entity_type = 'CUSTOMER' and entity_id = ? and action = 'UPDATE_CREDIT_LIMIT'""", customer);
		assertThat((String) credit.get("changes")).contains("creditLimit");
		assertThat((String) credit.get("details")).contains("50000").contains("150000");
	}

	// --- Consultation ---------------------------------------------------------------------------------

	@Test
	void theJournalIsSearchableExportableAndReservedToAuditors() throws Exception {
		idOf(this.mvc.perform(post("/api/products").with(admin()).contentType(MediaType.APPLICATION_JSON)
			.content(product("AUD-LST-1", 1000))));
		String second = idOf(this.mvc.perform(post("/api/products").with(admin()).contentType(MediaType.APPLICATION_JSON)
			.content(product("AUD-LST-2", 1000))));
		this.mvc.perform(put("/api/products/{id}", second).with(admin()).contentType(MediaType.APPLICATION_JSON)
			.content(product("AUD-LST-2", 1500)))
			.andExpect(status().isOk());
		String today = LocalDate.now(ZoneOffset.UTC).toString();

		this.mvc.perform(get("/api/audit-logs").with(admin()).param("userId", this.adminId.toString()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(3))
			.andExpect(jsonPath("$.items[0].action").value("UPDATE"));
		this.mvc.perform(get("/api/audit-logs").with(admin()).param("userId", this.adminId.toString())
			.param("actions", "CREATE,DELETE").param("modules", "CATALOG").param("status", "SUCCESS")
			.param("dateFrom", today).param("dateTo", today))
			.andExpect(jsonPath("$.total").value(2));
		this.mvc.perform(get("/api/audit-logs").with(admin()).param("userId", this.adminId.toString())
			.param("entityTypes", "PRODUCT").param("search", "lst-2"))
			.andExpect(jsonPath("$.total").value(2));
		this.mvc.perform(get("/api/audit-logs").with(admin()).param("userId", this.adminId.toString())
			.param("dateTo", "2000-01-01"))
			.andExpect(jsonPath("$.total").value(0));
		this.mvc.perform(get("/api/audit-logs").with(admin()).param("dateFrom", "hier"))
			.andExpect(status().isBadRequest());
		this.mvc.perform(get("/api/audit-logs").with(admin()).param("status", "MAYBE"))
			.andExpect(status().isBadRequest());
		this.mvc.perform(get("/api/audit-logs/{id}", UUID.randomUUID()).with(admin()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("AUDIT_LOG_NOT_FOUND"));

		this.mvc.perform(get("/api/audit-logs/stats").with(admin()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalToday").isNumber())
			.andExpect(jsonPath("$.failuresLast7Days").isNumber())
			.andExpect(jsonPath("$.mostActiveUser").isString());

		byte[] csv = this.mvc.perform(get("/api/audit-logs/export").with(admin())
			.param("userId", this.adminId.toString()))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Type", startsWith("text/csv")))
			.andExpect(header().string("Content-Disposition", startsWith("attachment")))
			.andReturn().getResponse().getContentAsByteArray();
		String text = new String(csv, StandardCharsets.UTF_8);
		assertThat(text).startsWith("﻿" + AuditLogQueryService.CSV_HEADER);
		assertThat(text.lines()).hasSize(4);
		assertThat(text).contains("AUD-LST-2 · Produit AUD-LST-2").contains(this.adminName);
		assertThat(this.jdbc.queryForObject("""
				select count(*) from audit_log where action = 'EXPORT' and module = 'AUDIT' and user_id = ?""",
				Integer.class, this.adminId)).as("l'export est tracé").isEqualTo(1);

		RequestPostProcessor manager = as(this.adminId, this.adminName, Set.of("GESTIONNAIRE"), Permissions.PRODUCT_READ,
				Permissions.REPORT_READ);
		this.mvc.perform(get("/api/audit-logs").with(manager)).andExpect(status().isForbidden());
		this.mvc.perform(get("/api/audit-logs/export").with(manager)).andExpect(status().isForbidden());
	}

	// --- Outils ------------------------------------------------------------------------------------

	private String product(String sku, int retailPrice) {
		return """
				{"sku": "%s", "name": "Produit %s", "categoryId": "%s", "purchasePrice": 500, "retailPrice": %d,
				 "wholesalePrice": 900, "alertThreshold": 2}""".formatted(sku, sku, this.category, retailPrice);
	}

	private String idOf(ResultActions result) throws Exception {
		JsonNode body = this.json.readTree(
				result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
		return body.get("id").asString();
	}

	private RequestPostProcessor admin() {
		return as(this.adminId, this.adminName, Set.of("ADMIN"), Permissions.ALL.toArray(String[]::new));
	}

	/** Requête authentifiée comme par un jeton : principal {@link CurrentUser} relu côté serveur. */
	private static RequestPostProcessor as(UUID id, String username, Set<String> roles, String... permissions) {
		CurrentUser user = new CurrentUser(id, username, roles, Set.of(permissions), null, Set.of());
		Jwt token = Jwt.withTokenValue("test").header("alg", "HS256").subject(id.toString()).build();
		Collection<SimpleGrantedAuthority> authorities = java.util.Arrays.stream(permissions)
			.map(SimpleGrantedAuthority::new)
			.toList();
		return authentication(new CurrentUserAuthentication(user, token, authorities));
	}

	private static List<String> concat(List<String> values, String extra) {
		List<String> all = new java.util.ArrayList<>(values);
		all.add(extra);
		return all;
	}

}
