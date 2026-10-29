package com.geststock.integration.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.exception.NotFoundException;
import com.geststock.platform.PlatformDb;
import com.geststock.platform.provisioning.ProvisionerAccess;
import com.geststock.support.PostgresTestConfiguration;
import com.geststock.tenancy.TenantContext;
import com.geststock.tenancy.TenantDataSourceRegistry;
import com.geststock.tenancy.TenantRef;
import com.geststock.tenancy.TenantSchemaRegistry;

import tools.jackson.databind.json.JsonMapper;

/**
 * Statut de la liaison à chaque appel et entreprise courante (lot 2, étape G7 ; v13-D6, v14-D10, C2), avec deux
 * entreprises réelles : A (l'entreprise de test historique) et B (provisionnée pour ce test, base dédiée). La liaison
 * est relue à chaque appel, sans cache ; l'entreprise courante vient uniquement de la liaison ; le contexte
 * d'entreprise est vidé après chaque requête.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "geststock.platform.provisioning.async=false")
@Import({ PostgresTestConfiguration.class, IntegrationLinkAccessIT.TenantTestController.class })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IntegrationLinkAccessIT {

	private static final String SUPER_ADMIN = "it_g7_superadmin";

	private static final String SUPER_ADMIN_PASSWORD = "Plateforme-Test-2026";

	private static final String BASE = "/api/integration/v1/test-tenant";

	private static final Set<String> SCOPES = Set.of("catalog:read", "orders:read");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private PlatformDb platform;

	@Autowired
	private LinkRegistry registry;

	@Autowired
	private IntegrationTokenService tokens;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TenantSchemaRegistry schemas;

	@Autowired
	private TenantDataSourceRegistry pools;

	@Autowired
	private ProvisionerAccess provisioner;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JsonMapper json;

	private TestLinks links;

	private TenantRef companyA;

	private TenantRef companyB;

	private UUID categoryA;

	private UUID categoryB;

	private final List<UUID> createdCompanies = new ArrayList<>();

	@BeforeAll
	void provisionCompanies() throws Exception {
		this.links = new TestLinks(this.platform, this.registry);
		UUID legacy = this.links.legacyCompany();
		this.companyA = new TenantRef(legacy, "test");
		this.companyB = provisionCompanyB();
		this.categoryA = TenantContext.callAs(this.companyA, () -> this.jdbc.queryForObject(
				"insert into category (name) values (?) returning id", UUID.class, "Catégorie A " + UUID.randomUUID()));
		this.categoryB = TenantContext.callAs(this.companyB, () -> this.jdbc.queryForObject(
				"insert into category (name) values (?) returning id", UUID.class, "Catégorie B " + UUID.randomUUID()));
	}

	@BeforeEach
	void nothingLeaksFromAPreviousTest() {
		assertThat(TenantContext.current()).isEmpty();
	}

	@AfterEach
	void cleanUpLinks() {
		this.links.cleanUp();
		this.platform.jdbc().sql("update platform.company set status = 'ACTIVE' where id = :id").param("id", this.companyA.companyId())
			.update();
	}

	@AfterAll
	void dropCompanyB() throws Exception {
		TenantContext.runAs(this.companyA, () -> this.jdbc.update("delete from category where id = ?", this.categoryA));
		for (UUID id : this.createdCompanies) {
			this.pools.close(id);
			var database = this.platform.jdbc()
				.sql("select database_name, runtime_role from platform.company_database where company_id = :id and mode = 'DEDICATED'")
				.param("id", id).query((rs, n) -> new String[] { rs.getString(1), rs.getString(2) }).optional();
			if (database.isPresent()) {
				try (Connection c = this.provisioner.server().getConnection(); Statement statement = c.createStatement()) {
					statement.execute("DROP DATABASE IF EXISTS \"" + database.get()[0] + "\" WITH (FORCE)");
					statement.execute("DROP ROLE IF EXISTS \"" + database.get()[1] + "\"");
				}
			}
			this.platform.inTransaction(() -> {
				for (String sql : List.of("delete from platform.provisioning_job where company_id = :id",
						"delete from platform.company_database where company_id = :id",
						"delete from platform.platform_audit_log where company_id = :id",
						"delete from platform.company where id = :id")) {
					this.platform.jdbc().sql(sql).param("id", id).update();
				}
			});
		}
		this.platform.jdbc().sql("delete from platform.platform_admin where username = :u").param("u", SUPER_ADMIN).update();
	}

	// --- Entreprise courante ------------------------------------------------------------------------------

	@Test
	void anActiveLinkOfAReadsTheDataOfA() throws Exception {
		String token = activeToken(this.companyA, SCOPES);

		call(BASE + "/whoami", token).andExpect(status().isOk())
			.andExpect(jsonPath("$.companyId").value(this.companyA.companyId().toString()))
			.andExpect(jsonPath("$.companyCode").value("test"));
		call(BASE + "/categories/" + this.categoryA, token).andExpect(status().isOk())
			.andExpect(jsonPath("$.companyCode").value("test"));
		assertThat(TenantContext.current()).as("contexte vidé après la requête").isEmpty();
	}

	@Test
	void aLinkOfACannotReadAnObjectOfBAndGetsTheSameAnswerAsForAMissingObject() throws Exception {
		String token = activeToken(this.companyA, SCOPES);
		String ofB = call(BASE + "/categories/" + this.categoryB, token).andExpect(status().isNotFound())
			.andReturn().getResponse().getContentAsString();
		String missing = call(BASE + "/categories/" + UUID.randomUUID(), token).andExpect(status().isNotFound())
			.andReturn().getResponse().getContentAsString();

		assertThat(this.json.readTree(ofB).get("code")).isEqualTo(this.json.readTree(missing).get("code"));
		assertThat(this.json.readTree(ofB).get("message")).isEqualTo(this.json.readTree(missing).get("message"));
		// Témoin : la liaison de B lit bien sa propre catégorie.
		call(BASE + "/categories/" + this.categoryB, activeToken(this.companyB, SCOPES)).andExpect(status().isOk())
			.andExpect(jsonPath("$.companyCode").value(this.companyB.code()));
	}

	@Test
	void noRequestHeaderOrParameterCanChangeTheCompany() throws Exception {
		String token = activeToken(this.companyA, SCOPES);

		this.mvc.perform(get(BASE + "/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.header("X-Company", this.companyB.code()).header("X-Company-Code", this.companyB.code())
			.header("X-Tenant-Id", this.companyB.companyId().toString())
			.param("company", this.companyB.code()).param("companyId", this.companyB.companyId().toString()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.companyId").value(this.companyA.companyId().toString()));
	}

	@Test
	void theCompanyContextIsClearedAfterAFailingRequest() throws Exception {
		call(BASE + "/boom", activeToken(this.companyA, SCOPES)).andExpect(status().isInternalServerError());

		assertThat(TenantContext.current()).as("contexte vidé après une exception").isEmpty();
	}

	// --- Statut de la liaison -----------------------------------------------------------------------------

	@Test
	void aLinkRevokedWhileItsTokenIsStillValidIsRefusedAtTheNextCall() throws Exception {
		Link link = this.links.active(this.links.client(), this.companyA.companyId(), "shop-revoked", SCOPES);
		String token = token(link, SCOPES);
		call(BASE + "/whoami", token).andExpect(status().isOk());

		this.registry.revoke(link.id(), RevocationReason.SHOP_REQUEST);

		call(BASE + "/whoami", token).andExpect(status().isUnauthorized())
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\""))
			.andExpect(jsonPath("$.code").value("LINK_REVOKED"));
	}

	@Test
	void aSuspendedLinkIsRefusedExceptOnRoutesAllowedDuringSuspension() throws Exception {
		Link link = this.links.active(this.links.client(), this.companyA.companyId(), "shop-suspended", SCOPES);
		String token = token(link, SCOPES);
		this.registry.suspend(link.id(), SuspensionReason.MANUAL);

		call(BASE + "/whoami", token).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("LINK_SUSPENDED"));
		call(BASE + "/during-suspension", token).andExpect(status().isOk())
			.andExpect(jsonPath("$.companyId").value(this.companyA.companyId().toString()));

		this.registry.reactivate(link.id(), new Authorizer(UUID.randomUUID(), "gerant"));
		call(BASE + "/whoami", token).andExpect(status().isOk());
	}

	@Test
	void aPendingUnknownOrForeignClientLinkIsAnInvalidToken() throws Exception {
		TestLinks.Client client = this.links.client();
		Link pending = this.links.pending(client, this.companyA.companyId(), "shop-pending", SCOPES);
		assertInvalidToken(call(BASE + "/whoami", this.tokens.issue(pending.id(), client.clientId(), SCOPES).value()));
		assertInvalidToken(call(BASE + "/whoami", this.tokens.issue(UUID.randomUUID(), client.clientId(), SCOPES).value()));
		Link active = this.links.active(client, this.companyA.companyId(), "shop-foreign", SCOPES);
		assertInvalidToken(call(BASE + "/whoami", this.tokens.issue(active.id(), "jcmb-autre-client", SCOPES).value()));
	}

	@Test
	void aCompanyThatIsNotActiveIsRefused() throws Exception {
		String token = activeToken(this.companyA, SCOPES);
		setCompanyStatus(this.companyA, "SUSPENDED");
		call(BASE + "/whoami", token).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMPANY_SUSPENDED"));
		setCompanyStatus(this.companyA, "PROVISIONING");
		call(BASE + "/whoami", token).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMPANY_UNAVAILABLE"));
	}

	@Test
	void aCompanyWhoseDatabaseIsBeingMigratedGetsA503() throws Exception {
		String token = activeToken(this.companyA, SCOPES);
		this.schemas.upgrading(this.companyA, 1);
		try {
			call(BASE + "/whoami", token).andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("SCHEMA_UPGRADE_IN_PROGRESS"));
		}
		finally {
			this.schemas.upToDate(this.companyA, 0, 0);
		}
		call(BASE + "/whoami", token).andExpect(status().isOk());
	}

	// --- Portées effectives ---------------------------------------------------------------------------------

	@Test
	void aScopeRemovedFromTheLinkIsRefusedEvenWithATokenThatStillCarriesIt() throws Exception {
		Link link = this.links.active(this.links.client(), this.companyA.companyId(), "shop-scopes", SCOPES);
		String token = token(link, SCOPES);
		call(BASE + "/whoami", token).andExpect(status().isOk());

		this.platform.jdbc().sql("update platform.integration_link set scopes = ARRAY['orders:read']::varchar[] where id = :id")
			.param("id", link.id()).update();

		call(BASE + "/whoami", token).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("INSUFFICIENT_SCOPE"));
	}

	// --- Route qui ne touche que la base plateforme (C2 : POST /link/revoke) --------------------------------

	@Test
	void aPlatformOnlyRouteRunsWithoutCompanyContext() throws Exception {
		String token = activeToken(this.companyA, SCOPES);

		postAs(BASE + "/revoke-like", token).andExpect(status().isOk())
			.andExpect(jsonPath("$.companyContext").value(false));
		call(BASE + "/during-suspension", token).andExpect(status().isOk());
	}

	@Test
	void aPlatformOnlyRouteStaysAvailableWhenTheCompanyIsSuspendedOrUnavailableButGetLinkDoesNot() throws Exception {
		String token = activeToken(this.companyA, SCOPES);

		setCompanyStatus(this.companyA, "SUSPENDED");
		postAs(BASE + "/revoke-like", token).andExpect(status().isOk()).andExpect(jsonPath("$.companyContext").value(false));
		call(BASE + "/during-suspension", token).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("COMPANY_SUSPENDED"));

		setCompanyStatus(this.companyA, "PROVISIONING");
		postAs(BASE + "/revoke-like", token).andExpect(status().isOk()).andExpect(jsonPath("$.companyContext").value(false));
		call(BASE + "/during-suspension", token).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("COMPANY_UNAVAILABLE"));
	}

	@Test
	void aPlatformOnlyRouteStaysAvailableDuringAMigrationButGetLinkDoesNot() throws Exception {
		String token = activeToken(this.companyA, SCOPES);
		this.schemas.upgrading(this.companyA, 1);
		try {
			postAs(BASE + "/revoke-like", token).andExpect(status().isOk()).andExpect(jsonPath("$.companyContext").value(false));
			call(BASE + "/during-suspension", token).andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("SCHEMA_UPGRADE_IN_PROGRESS"));
		}
		finally {
			this.schemas.upToDate(this.companyA, 0, 0);
		}
	}

	@Test
	void aPlatformOnlyRouteKeepsTheTokenLinkAndScopeChecks() throws Exception {
		Link link = this.links.active(this.links.client(), this.companyA.companyId(), "shop-revoke-like", SCOPES);
		String token = token(link, SCOPES);
		this.registry.suspend(link.id(), SuspensionReason.MANUAL);
		postAs(BASE + "/revoke-like", token).andExpect(status().isOk());

		this.platform.jdbc().sql("update platform.integration_link set scopes = ARRAY['orders:write']::varchar[] where id = :id")
			.param("id", link.id()).update();
		postAs(BASE + "/revoke-like", token).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("INSUFFICIENT_SCOPE"));

		this.registry.revoke(link.id(), RevocationReason.SHOP_REQUEST);
		postAs(BASE + "/revoke-like", token).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("LINK_REVOKED"));
		this.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(BASE + "/revoke-like"))
			.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
		TestLinks.Client client = this.links.client();
		Link pending = this.links.pending(client, this.companyA.companyId(), "shop-revoke-pending", SCOPES);
		postAs(BASE + "/revoke-like", this.tokens.issue(pending.id(), client.clientId(), SCOPES).value())
			.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
	}

	// --- Outils ---------------------------------------------------------------------------------------------

	private ResultActions postAs(String path, String token) throws Exception {
		return this.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
	}

	private String activeToken(TenantRef company, Set<String> scopes) {
		Link link = this.links.active(this.links.client(), company.companyId(), "shop-" + UUID.randomUUID(), scopes);
		return token(link, scopes);
	}

	private String token(Link link, Set<String> scopes) {
		String clientId = this.platform.jdbc().sql("select client_id from platform.integration_client where id = :id")
			.param("id", link.clientId()).query(String.class).single();
		return this.tokens.issue(link.id(), clientId, scopes).value();
	}

	private ResultActions call(String path, String token) throws Exception {
		return this.mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
	}

	private static void assertInvalidToken(ResultActions actions) throws Exception {
		actions.andExpect(status().isUnauthorized())
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\""))
			.andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
	}

	private void setCompanyStatus(TenantRef company, String status) {
		this.platform.jdbc().sql("update platform.company set status = :s where id = :id").param("s", status)
			.param("id", company.companyId()).update();
	}

	private TenantRef provisionCompanyB() throws Exception {
		this.platform.jdbc().sql("delete from platform.platform_admin where username = :u").param("u", SUPER_ADMIN).update();
		this.platform.jdbc()
			.sql("insert into platform.platform_admin (username, full_name, password_hash, must_change_password) values (:u, 'IT', :h, false)")
			.param("u", SUPER_ADMIN).param("h", this.passwordEncoder.encode(SUPER_ADMIN_PASSWORD)).update();
		String login = this.mvc.perform(post("/api/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\": \"" + SUPER_ADMIN + "\", \"password\": \"" + SUPER_ADMIN_PASSWORD + "\"}"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String superToken = this.json.readTree(login).get("accessToken").asString();
		String code = "it-g7-" + UUID.randomUUID().toString().substring(0, 8);
		String created = this.mvc.perform(post("/api/platform/companies").header(HttpHeaders.AUTHORIZATION, "Bearer " + superToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"code": "%s", "legalName": "Commerce B SARL", "tradeName": "Commerce B", "ownerLastName": "Kouassi",
					 "adminUsername": "admin"}""".formatted(code)))
			.andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ACTIVE"))
			.andReturn().getResponse().getContentAsString();
		UUID id = UUID.fromString(this.json.readTree(created).get("id").asString());
		this.createdCompanies.add(id);
		return new TenantRef(id, code);
	}

	/** Routes d'intégration de test, chargées seulement dans ce contexte : elles lisent l'entreprise courante. */
	@RestController
	@RequestMapping(BASE)
	static class TenantTestController {

		private final JdbcTemplate jdbc;

		TenantTestController(JdbcTemplate jdbc) {
			this.jdbc = jdbc;
		}

		@GetMapping("/whoami")
		@RequiresScope("catalog:read")
		Map<String, Object> whoami() {
			TenantRef tenant = TenantContext.require();
			return Map.of("companyId", tenant.companyId().toString(), "companyCode", tenant.code());
		}

		@GetMapping("/categories/{id}")
		@RequiresScope("catalog:read")
		Map<String, Object> category(@PathVariable UUID id) {
			List<String> names = this.jdbc.queryForList("select name from category where id = ?", String.class, id);
			if (names.isEmpty()) {
				throw new NotFoundException("Catégorie introuvable.");
			}
			return Map.of("name", names.get(0), "companyCode", TenantContext.require().code());
		}

		@GetMapping("/during-suspension")
		@RequiresScope(value = RequiresScope.ANY, allowedWhileSuspended = true)
		Map<String, Object> duringSuspension() {
			return Map.of("companyId", TenantContext.require().companyId().toString());
		}

		/** Marquée comme POST /link/revoke : base plateforme seulement, aucune entreprise courante. */
		@org.springframework.web.bind.annotation.PostMapping("/revoke-like")
		@RequiresScope(value = RequiresScope.ANY, allowedWhileSuspended = true, platformOnly = true)
		Map<String, Object> revokeLike() {
			return Map.of("companyContext", TenantContext.current().isPresent());
		}

		@GetMapping("/boom")
		@RequiresScope("catalog:read")
		Map<String, Object> boom() {
			TenantContext.require();
			throw new IllegalStateException("panne simulée");
		}

	}

}
