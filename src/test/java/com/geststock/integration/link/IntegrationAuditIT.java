package com.geststock.integration.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
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
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.geststock.audit.AuditEvent;
import com.geststock.audit.AuditService;
import com.geststock.common.security.Permissions;
import com.geststock.platform.PlatformDb;
import com.geststock.platform.provisioning.ProvisionerAccess;
import com.geststock.role.RoleService;
import com.geststock.role.dto.CreateRoleRequest;
import com.geststock.role.dto.UpdateRoleRequest;
import com.geststock.security.CurrentUser;
import com.geststock.security.CurrentUserAuthentication;
import com.geststock.security.JwtTokenService;
import com.geststock.support.PostgresTestConfiguration;
import com.geststock.tenancy.TenantContext;
import com.geststock.tenancy.TenantDataSourceRegistry;
import com.geststock.tenancy.TenantRef;
import com.geststock.user.UserAccountService;
import com.geststock.user.UserService;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Audit des liaisons (lot 2, étape G12). Trace de référence dans la base plateforme
 * ({@code platform.integration_audit_log}), dans la transaction du changement de la liaison ; copie des événements qui
 * concernent l'entreprise dans son propre journal ({@code audit_log}, lu par l'API d'audit), après le commit, au mieux.
 * Auteurs : personne (identifiant et nom), boutique (via sa liaison), système, client. Jamais de secret, d'empreinte,
 * de code, de {@code request_uri}, de jeton ni d'en-tête {@code Authorization} ; le motif libre de la boutique est
 * dans la trace et absent des journaux applicatifs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "geststock.platform.provisioning.async=false")
@Import(PostgresTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IntegrationAuditIT {

	private static final String SUPER_ADMIN = "it_g12_superadmin";

	private static final String SUPER_ADMIN_PASSWORD = "Plateforme-Test-2026";

	private static final String REQUESTS = "/api/integration-links/authorization-requests";

	private static final String LINKS = "/api/integration-links";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private PlatformDb platform;

	@Autowired
	private LinkRegistry registry;

	/** Espion : copie vers le journal de l'entreprise impossible. */
	@MockitoSpyBean
	private AuditService companyAudit;

	@Autowired
	private UserService users;

	@Autowired
	private RoleService roles;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private org.springframework.transaction.support.TransactionTemplate transactions;

	@Autowired
	private JwtTokenService userTokens;

	@Autowired
	private UserAccountService accounts;

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

	private User adminA;

	private User adminB;

	private UUID mainA;

	private String clientSecret;

	private TestLinks.Client client;

	private OffsetDateTime startedAt;

	private final List<UUID> createdUsers = new ArrayList<>();

	private final List<UUID> createdRoles = new ArrayList<>();

	private final List<UUID> createdCompanies = new ArrayList<>();

	private final List<String> sensitive = java.util.Collections.synchronizedList(new ArrayList<>());

	private final Map<Logger, Level> levels = new LinkedHashMap<>();

	private ListAppender<ILoggingEvent> logs;

	@BeforeAll
	void provision() throws Exception {
		this.links = new TestLinks(this.platform, this.registry);
		this.companyA = new TenantRef(this.links.legacyCompany(), "test");
		this.companyB = provisionCompanyB();
		this.adminA = user(this.companyA, "ADMIN");
		this.adminB = user(this.companyB, "ADMIN");
		this.mainA = this.jdbc.queryForObject("select id from warehouse where main", UUID.class);
	}

	@BeforeEach
	void setUp() {
		this.startedAt = this.platform.jdbc().sql("select now()").query(OffsetDateTime.class).single();
		this.clientSecret = remember("secret-g12-" + UUID.randomUUID());
		this.client = this.links.client(this.clientSecret, null, Set.of("catalog:read", "orders:read"));
		LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
		for (Logger logger : context.getLoggerList()) {
			// Sauf le pilote PostgreSQL, que la configuration fixe à INFO : sa trace protocolaire afficherait chaque
			// valeur liée (G12) ; le test vérifie justement que ce niveau protège le motif libre.
			if (logger.getLevel() != null && !logger.getName().equals("org.postgresql")) {
				this.levels.put(logger, logger.getLevel());
				logger.setLevel(Level.TRACE);
			}
		}
		this.logs = new ListAppender<>();
		this.logs.start();
		context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).addAppender(this.logs);
	}

	@AfterEach
	void tearDown() {
		((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).detachAppender(this.logs);
		this.levels.forEach(Logger::setLevel);
		this.levels.clear();
		try {
			// Aucune valeur sensible dans les traces écrites pendant le test (référence et journal de l'entreprise).
			List<String> traces = this.platform.jdbc().sql("""
					select concat_ws(' | ', action, actor_name, announced_client, external_shop_id, reason, details::text)
					from platform.integration_audit_log where occurred_at >= :since""").param("since", this.startedAt)
				.query(String.class).list();
			List<String> companyTraces = this.jdbc.queryForList("""
					select concat_ws(' | ', action, username, entity_label, details, meta::text, before_state::text,
					  after_state::text) from audit_log where created_at >= ?""", String.class,
					java.sql.Timestamp.from(this.startedAt.toInstant()));
			for (String trace : concat(traces, companyTraces)) {
				for (String value : this.sensitive) {
					assertThat(trace).as("valeur sensible dans une trace").doesNotContain(value);
				}
			}
		}
		finally {
			this.sensitive.clear();
			this.links.cleanUp();
		}
	}

	@AfterAll
	void cleanUp() throws Exception {
		for (UUID user : this.createdUsers) {
			this.jdbc.update("delete from app_user where id = ?", user);
		}
		for (UUID role : this.createdRoles) {
			this.jdbc.update("delete from role where id = ?", role);
		}
		dropCompanyB();
	}

	// --- Cycle de vie : une trace de référence par événement, copie dans l'audit de l'entreprise ---------------

	@Test
	void approvalActivationSuspensionReactivationAndRevocationAreEachTracedOnce() throws Exception {
		String verifier = verifier();
		String requestUri = par("shop-g12-life", verifier);
		String code = approve(requestUri);
		UUID link = linkOf(requestUri);
		String secret = remember(exchange(code, verifier).get("link_secret").asString());

		this.mvc.perform(bearer(post(LINKS + "/{id}/suspend", link), token(this.adminA))).andExpect(status().isOk());
		this.mvc.perform(bearer(jsonPost(LINKS + "/" + link + "/reactivate", Map.of("warehouseIds", List.of(this.mainA))),
				token(this.adminA))).andExpect(status().isOk());
		this.mvc.perform(bearer(post(LINKS + "/{id}/revoke", link), token(this.adminA))).andExpect(status().isOk());

		List<Map<String, Object>> traces = traces(link);
		assertThat(traces).extracting(t -> t.get("action")).containsExactly("LINK_REQUEST_APPROVED", "LINK_ACTIVATED",
				"LINK_SUSPENDED", "LINK_REACTIVATED", "LINK_REVOKED");
		for (Map<String, Object> trace : traces) {
			assertThat(trace.get("company_id")).isEqualTo(this.companyA.companyId());
			assertThat(trace.get("external_shop_id")).isEqualTo("shop-g12-life");
		}
		assertUser(traces.get(0), this.adminA);
		assertThat(details(traces.get(0)).get("scopes").toString()).contains("catalog:read");
		assertThat(details(traces.get(0)).get("warehouses").toString()).contains(this.mainA.toString());
		assertThat(traces.get(1).get("actor_type")).as("activation : la boutique via sa liaison").isEqualTo("SHOP");
		assertThat(traces.get(1).get("actor_id")).isEqualTo(link);
		assertUser(traces.get(2), this.adminA);
		assertThat(details(traces.get(2)).get("suspensionReason").asString()).isEqualTo("MANUAL");
		assertUser(traces.get(3), this.adminA);
		assertThat(details(traces.get(3)).get("authorizedBy").asString()).isEqualTo(this.adminA.id().toString());
		assertThat(details(traces.get(3)).get("warehouses").toString()).contains(this.mainA.toString());
		assertUser(traces.get(4), this.adminA);
		assertThat(details(traces.get(4)).get("revocationReason").asString()).isEqualTo("GESTSTOCK_ADMIN");

		// Journal de l'entreprise A : approbation, suspension, réactivation, révocation ; rien chez B.
		List<JsonNode> companyEntries = companyAudit(this.adminA, link);
		assertThat(companyEntries).extracting(e -> e.get("action").asString()).containsExactlyInAnyOrder(
				"LINK_REQUEST_APPROVED", "LINK_SUSPENDED", "LINK_REACTIVATED", "LINK_REVOKED");
		assertThat(companyEntries).allSatisfy(e -> {
			assertThat(e.get("username").asString()).isEqualTo(this.adminA.username());
			assertThat(e.get("module").asString()).isEqualTo("SECURITY");
			assertThat(e.get("entityType").asString()).isEqualTo("INTEGRATION_LINK");
		});
		assertThat(companyAudit(this.adminB, link)).as("audit de l'entreprise B").isEmpty();
		assertThat(secret).isNotBlank();
	}

	@Test
	void aDenialIsTracedWithoutLink() throws Exception {
		String requestUri = par("shop-g12-deny", verifier());
		this.mvc.perform(bearer(jsonPost(REQUESTS + "/deny", Map.of("requestUri", requestUri)), token(this.adminA)))
			.andExpect(status().isOk());

		List<Map<String, Object>> traces = tracesOfShop("shop-g12-deny");
		assertThat(traces).extracting(t -> t.get("action")).containsExactly("LINK_REQUEST_DENIED");
		assertThat(traces.get(0).get("link_id")).isNull();
		assertThat(traces.get(0).get("company_id")).isEqualTo(this.companyA.companyId());
		assertUser(traces.get(0), this.adminA);
		assertThat(companyAuditSearch(this.adminA, "shop-g12-deny")).extracting(e -> e.get("action").asString())
			.containsExactly("LINK_REQUEST_DENIED");
		assertThat(companyAuditSearch(this.adminB, "shop-g12-deny")).isEmpty();
	}

	@Test
	void anAutomaticSuspensionIsTracedWithTheSystemAsAuthor() throws Exception {
		UUID responsible = user(this.companyA, role(Permissions.INTEGRATION_MANAGE)).id();
		Link link = this.links.pending(this.client, this.companyA.companyId(), "shop-g12-auto", Set.of("catalog:read"),
				new Authorizer(responsible, "g12.responsable"));
		this.registry.activate(link.id());

		as(this.adminA, () -> this.users.setActive(responsible, false));

		List<Map<String, Object>> traces = traces(link.id());
		assertThat(traces).extracting(t -> t.get("action")).contains("LINK_SUSPENDED");
		Map<String, Object> suspension = traces.get(traces.size() - 1);
		assertThat(suspension.get("actor_type")).isEqualTo("SYSTEM");
		assertThat(suspension.get("actor_id")).isNull();
		assertThat(details(suspension).get("suspensionReason").asString()).isEqualTo("AUTHORIZER_LOST_ACCESS");
		List<JsonNode> companyEntries = companyAudit(this.adminA, link.id());
		assertThat(companyEntries).extracting(e -> e.get("action").asString()).containsExactly("LINK_SUSPENDED");
		assertThat(companyEntries.get(0).get("userId").isNull()).as("aucun utilisateur ne l'a faite").isTrue();
		assertThat(companyEntries.get(0).get("username").asString()).isEqualTo("système");
	}

	@Test
	void aShopRevocationKeepsItsReasonInTheTraceAndOutOfTheLogs() throws Exception {
		Link link = this.links.pending(this.client, this.companyA.companyId(), "shop-g12-shop", Set.of("catalog:read"));
		this.registry.activate(link.id());
		String token = remember(linkToken(link.id()));
		String reason = remember("Motif libre de la boutique " + UUID.randomUUID() + " — « fermeture »");

		this.mvc.perform(bearer(jsonPost("/api/integration/v1/link/revoke", Map.of("reason", reason)), token))
			.andExpect(status().isOk());

		List<Map<String, Object>> traces = traces(link.id());
		Map<String, Object> revocation = traces.get(traces.size() - 1);
		assertThat(revocation.get("action")).isEqualTo("LINK_REVOKED");
		assertThat(revocation.get("actor_type")).isEqualTo("SHOP");
		assertThat(revocation.get("actor_id")).isEqualTo(link.id());
		assertThat(revocation.get("actor_name")).isEqualTo("shop-g12-shop");
		assertThat(revocation.get("reason")).as("motif tel quel").isEqualTo(reason);
		assertThat(details(revocation).get("revocationReason").asString()).isEqualTo("SHOP_REQUEST");
		List<JsonNode> companyEntries = companyAudit(this.adminA, link.id());
		assertThat(companyEntries).extracting(e -> e.get("action").asString()).containsExactly("LINK_REVOKED");
		assertThat(companyEntries.get(0).get("username").asString()).isEqualTo("liaison:" + link.id());
		assertThat(companyEntries.get(0).get("userId").isNull()).isTrue();
		// Le motif est dans les traces, donc retiré de la recherche des valeurs sensibles des traces.
		this.sensitive.remove(reason);
		assertThat(this.logs.list).as("motif absent des journaux applicatifs")
			.noneMatch(event -> String.valueOf(event.getFormattedMessage()).contains(reason));
	}

	@Test
	void anExpiredPendingLinkIsTracedAsASystemRevocation() throws Exception {
		Link pending = this.links.pending(this.client, this.companyA.companyId(), "shop-g12-expired", Set.of("catalog:read"));
		this.platform.jdbc().sql("update platform.integration_link set created_at = now() - interval '10 minutes' where id = :id")
			.param("id", pending.id()).update();

		par("shop-g12-expired", verifier());

		List<Map<String, Object>> traces = traces(pending.id());
		assertThat(traces).extracting(t -> t.get("action")).containsExactly("LINK_REVOKED");
		assertThat(traces.get(0).get("actor_type")).isEqualTo("SYSTEM");
		assertThat(details(traces.get(0)).get("revocationReason").asString()).isEqualTo("EXPIRED");
	}

	// --- Tentatives suspectes ---------------------------------------------------------------------------------

	@Test
	void aReplayedCodeAPkceFailureAndAWrongRedirectAreTracedWithoutSecrets() throws Exception {
		String verifier = verifier();
		String requestUri = par("shop-g12-replay", verifier);
		String code = approve(requestUri);
		UUID link = linkOf(requestUri);

		// redirect_uri différente, puis vérificateur faux : le code n'est pas consommé, l'échec est tracé.
		this.mvc.perform(form(Map.of("grant_type", "authorization_code", "code", code, "redirect_uri",
				"https://ailleurs.example.com/retour", "code_verifier", verifier))).andExpect(status().isBadRequest());
		this.mvc.perform(form(Map.of("grant_type", "authorization_code", "code", code, "redirect_uri",
				TestLinks.REDIRECT_URI, "code_verifier", verifier()))).andExpect(status().isBadRequest());
		remember(exchange(code, verifier).get("link_secret").asString());
		this.mvc.perform(form(Map.of("grant_type", "authorization_code", "code", code, "redirect_uri",
				TestLinks.REDIRECT_URI, "code_verifier", verifier))).andExpect(status().isBadRequest());

		List<Map<String, Object>> traces = traces(link);
		assertThat(traces).extracting(t -> t.get("action")).containsExactly("LINK_REQUEST_APPROVED",
				"CODE_EXCHANGE_REJECTED", "CODE_EXCHANGE_REJECTED", "LINK_ACTIVATED", "LINK_REVOKED", "CODE_REPLAY_DETECTED");
		assertThat(details(traces.get(1)).get("failure").asString()).isEqualTo("REDIRECT_URI");
		assertThat(details(traces.get(2)).get("failure").asString()).isEqualTo("PKCE");
		assertThat(traces.get(1).get("severity")).isEqualTo("WARNING");
		assertThat(details(traces.get(4)).get("revocationReason").asString()).isEqualTo("CODE_REPLAYED");
		assertThat(traces.get(5).get("severity")).isEqualTo("CRITICAL");
		assertThat(traces.get(5).get("actor_type")).isEqualTo("CLIENT");
		assertThat(traces.get(5).get("actor_name")).isEqualTo(this.client.clientId());
	}

	@Test
	void anInvalidClientIsTracedWithTheAnnouncedIdentifierAndIp() throws Exception {
		this.mvc.perform(post("/api/integration/v1/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.header(HttpHeaders.AUTHORIZATION, basic(this.client.clientId(), remember("mauvais-secret-" + UUID.randomUUID())))
			.param("grant_type", "client_credentials")).andExpect(status().isUnauthorized());

		List<Map<String, Object>> traces = this.platform.jdbc().sql("""
				select * from platform.integration_audit_log
				where action = 'CLIENT_AUTHENTICATION_FAILED' and announced_client = :client and occurred_at >= :since""")
			.param("client", this.client.clientId()).param("since", this.startedAt).query().listOfRows();
		assertThat(traces).hasSize(1);
		assertThat(traces.get(0).get("actor_type")).isEqualTo("CLIENT");
		assertThat(traces.get(0).get("severity")).isEqualTo("WARNING");
		assertThat(traces.get(0).get("ip_address")).isEqualTo("127.0.0.1");
		assertThat(traces.get(0).get("company_id")).isNull();
	}

	// --- Transactions -----------------------------------------------------------------------------------------

	@Test
	void anAbortedChangeLeavesNoTraceAndATraceNeverExistsWithoutItsChange() throws Exception {
		// Approbation refusée (portée non demandée) : décision et liaison annulées, aucune trace.
		String requestUri = par("shop-g12-aborted", verifier());
		this.mvc.perform(bearer(jsonPost(REQUESTS + "/approve", Map.of("requestUri", requestUri, "scopes",
				List.of("orders:write"), "warehouseIds", List.of(this.mainA))), token(this.adminA)))
			.andExpect(status().isUnprocessableContent());
		assertThat(tracesOfShop("shop-g12-aborted")).isEmpty();

		// Suspension : la trace et le changement réussissent ou échouent ensemble (même transaction).
		Link link = this.links.pending(this.client, this.companyA.companyId(), "shop-g12-together", Set.of("catalog:read"));
		this.registry.activate(link.id());
		this.platform.jdbc().sql("""
				alter table platform.integration_audit_log
				  add constraint ck_integration_audit_log_g12_test check (action <> 'LINK_SUSPENDED') not valid""").update();
		try {
			this.mvc.perform(bearer(post(LINKS + "/{id}/suspend", link.id()), token(this.adminA)))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).as("suspension refusée").isNotEqualTo(200));
		}
		finally {
			this.platform.jdbc().sql("alter table platform.integration_audit_log drop constraint ck_integration_audit_log_g12_test")
				.update();
		}
		assertThat(this.registry.find(link.id()).orElseThrow().status()).as("suspension annulée avec sa trace")
			.isEqualTo(LinkStatus.ACTIVE);
		assertThat(traces(link.id())).isEmpty();
	}

	@Test
	void aFailedCopyToTheCompanyAuditKeepsTheChangeAndItsReferenceTrace() throws Exception {
		Link link = this.links.pending(this.client, this.companyA.companyId(), "shop-g12-copy", Set.of("catalog:read"));
		this.registry.activate(link.id());
		// Poser l'échec dans une transaction : l'espion est enveloppé par le proxy transactionnel (MANDATORY).
		this.transactions.executeWithoutResult(status -> {
			doThrow(new IllegalStateException("journal de l'entreprise indisponible (test)")).when(this.companyAudit)
				.record(any(AuditEvent.class));
			doThrow(new IllegalStateException("journal de l'entreprise indisponible (test)")).when(this.companyAudit)
				.machine(any(AuditEvent.class), anyString());
		});

		this.mvc.perform(bearer(post(LINKS + "/{id}/suspend", link.id()), token(this.adminA))).andExpect(status().isOk());

		assertThat(this.registry.find(link.id()).orElseThrow().status()).isEqualTo(LinkStatus.SUSPENDED);
		assertThat(traces(link.id())).extracting(t -> t.get("action")).containsExactly("LINK_SUSPENDED");
		assertThat(this.logs.list).as("WARN de la copie").anyMatch(event -> event.getLevel() == Level.WARN
				&& event.getFormattedMessage().contains(link.id().toString()));
		org.mockito.Mockito.reset(this.companyAudit);
		assertThat(companyAudit(this.adminA, link.id())).isEmpty();
	}

	// --- Rôles et integration:manage (vérification en suspens depuis G1) ---------------------------------------

	@Test
	void roleChangesTouchingIntegrationManageAreTraced() throws Exception {
		String code = "G12_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
		UUID created = as(this.adminA, () -> this.roles.create(new CreateRoleRequest(code, "Essai G12", null,
				List.of(Permissions.INTEGRATION_MANAGE))).id());
		this.createdRoles.add(created);
		String other = role(Permissions.STOCK_READ);
		as(this.adminA, () -> this.roles.update(roleId(other), new UpdateRoleRequest("Essai G12 bis", null,
				List.of(Permissions.STOCK_READ, Permissions.INTEGRATION_MANAGE))));
		UUID holder = user(this.companyA, "CAISSIER").id();
		as(this.adminA, () -> this.users.assignRoles(holder, List.of(code)));

		Map<String, Object> creation = this.jdbc.queryForMap(
				"select details, after_state::text as after from audit_log where action = 'CREATE' and entity_type = 'ROLE' and entity_id = ?",
				created.toString());
		assertThat((String) creation.get("after")).contains(Permissions.INTEGRATION_MANAGE);
		String added = this.jdbc.queryForObject(
				"select meta::text from audit_log where action = 'UPDATE' and entity_type = 'ROLE' and entity_id = ?", String.class,
				roleId(other).toString());
		assertThat(added).contains("addedPermissions").contains(Permissions.INTEGRATION_MANAGE);
		String assignment = this.jdbc.queryForObject(
				"select details from audit_log where action = 'ASSIGN_ROLES' and entity_id = ?", String.class, holder.toString());
		assertThat(assignment).contains(code);
	}

	// --- Outils ---------------------------------------------------------------------------------------------

	private record User(UUID id, String username, TenantRef company) {
	}

	private User user(TenantRef company, String role) {
		String username = "g12." + role.toLowerCase().replace('_', '.') + "." + UUID.randomUUID().toString().substring(0, 6);
		UUID id = TenantContext.callAs(company, () -> {
			UUID createdUser = this.jdbc.queryForObject(
					"insert into app_user (username, last_name, must_change_password) values (?, 'Essai G12', false) returning id",
					UUID.class, username);
			this.jdbc.update("insert into user_role (user_id, role_id) select ?, id from role where code = ?", createdUser, role);
			return createdUser;
		});
		if (company.equals(this.companyA)) {
			this.createdUsers.add(id);
		}
		return new User(id, username, company);
	}

	private String role(String... permissions) {
		String code = "G12_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
		UUID id = this.jdbc.queryForObject("insert into role (code, label) values (?, 'Essai G12') returning id", UUID.class, code);
		for (String permission : permissions) {
			this.jdbc.update("insert into role_permission (role_id, permission_id) select ?, id from permission where code = ?", id,
					permission);
		}
		this.createdRoles.add(id);
		return code;
	}

	private UUID roleId(String code) {
		return this.jdbc.queryForObject("select id from role where code = ?", UUID.class, code);
	}

	/** Au nom de la personne (principal {@link CurrentUser}, toutes les permissions). */
	private <T> T as(User user, java.util.function.Supplier<T> action) {
		CurrentUser principal = new CurrentUser(user.id(), user.username(), Set.of("ADMIN"), Set.copyOf(Permissions.ALL), null,
				Set.of());
		Jwt token = Jwt.withTokenValue("test").header("alg", "HS256").subject(user.id().toString()).build();
		SecurityContext previous = SecurityContextHolder.getContext();
		try {
			SecurityContext context = SecurityContextHolder.createEmptyContext();
			context.setAuthentication(new CurrentUserAuthentication(principal, token,
					Permissions.ALL.stream().map(SimpleGrantedAuthority::new).toList()));
			SecurityContextHolder.setContext(context);
			return action.get();
		}
		finally {
			SecurityContextHolder.setContext(previous);
		}
	}

	private String token(User user) {
		return remember(TenantContext.callAs(user.company(),
				() -> this.userTokens.issue(this.accounts.findById(user.id()).orElseThrow()).value()));
	}

	private String linkToken(UUID link) throws Exception {
		return this.json.readTree(this.mvc.perform(post("/api/integration/v1/oauth/token")
			.contentType(MediaType.APPLICATION_FORM_URLENCODED).header(HttpHeaders.AUTHORIZATION, clientBasic())
			.param("grant_type", "client_credentials").param("link_id", link.toString())
			.param("link_secret", remember(rotateSecret(link)))).andExpect(status().isOk()).andReturn().getResponse()
			.getContentAsString()).get("access_token").asString();
	}

	/** Secret connu pour une liaison activée par le registre (empreinte posée directement). */
	private String rotateSecret(UUID link) {
		String secret = "secret-liaison-g12-" + UUID.randomUUID();
		this.platform.jdbc().sql("update platform.integration_link set secret_hash = :hash where id = :id")
			.param("hash", LinkRegistry.sha256Hex(secret)).param("id", link).update();
		return secret;
	}

	private String par(String shop, String verifier) throws Exception {
		JsonNode body = this.json.readTree(this.mvc.perform(post("/api/integration/v1/oauth/par")
			.contentType(MediaType.APPLICATION_FORM_URLENCODED).header(HttpHeaders.AUTHORIZATION, clientBasic())
			.param("response_type", "code").param("redirect_uri", TestLinks.REDIRECT_URI)
			.param("code_challenge", challenge(verifier)).param("code_challenge_method", "S256").param("scope", "catalog:read")
			.param("state", "s".repeat(40)).param("shop_id", shop).param("shop_name", "Boutique " + shop))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
		return remember(body.get("request_uri").asString());
	}

	/** Approbation par l'API (G10) ; renvoie le code de l'URL de retour. */
	private String approve(String requestUri) throws Exception {
		JsonNode body = this.json.readTree(this.mvc.perform(bearer(jsonPost(REQUESTS + "/approve", Map.of("requestUri", requestUri,
				"scopes", List.of("catalog:read"), "warehouseIds", List.of(this.mainA))), token(this.adminA)))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		String query = URI.create(body.get("redirectUrl").asString()).getRawQuery();
		for (String pair : query.split("&")) {
			if (pair.startsWith("code=")) {
				return remember(java.net.URLDecoder.decode(pair.substring(5), StandardCharsets.UTF_8));
			}
		}
		throw new AssertionError("code absent de l'URL de retour");
	}

	private JsonNode exchange(String code, String verifier) throws Exception {
		JsonNode body = this.json.readTree(this.mvc.perform(form(Map.of("grant_type", "authorization_code", "code", code,
				"redirect_uri", TestLinks.REDIRECT_URI, "code_verifier", verifier))).andExpect(status().isOk()).andReturn()
			.getResponse().getContentAsString());
		remember(body.get("access_token").asString());
		return body;
	}

	private MockHttpServletRequestBuilder form(Map<String, String> params) {
		MockHttpServletRequestBuilder request = post("/api/integration/v1/oauth/token")
			.contentType(MediaType.APPLICATION_FORM_URLENCODED).header(HttpHeaders.AUTHORIZATION, clientBasic());
		params.forEach(request::param);
		return request;
	}

	private UUID linkOf(String requestUri) {
		return this.platform.jdbc().sql("""
				select c.link_id from platform.integration_authorization_code c
				join platform.integration_authorization_request r on r.id = c.request_id
				where r.request_uri_hash = :hash""").param("hash", LinkRegistry.sha256Hex(requestUri)).query(UUID.class).single();
	}

	private List<Map<String, Object>> traces(UUID link) {
		return this.platform.jdbc().sql("""
				select action, severity, company_id, link_id, external_shop_id, actor_type, actor_id, actor_name, reason,
				  details::text as details
				from platform.integration_audit_log where link_id = :link order by occurred_at, id""")
			.param("link", link).query().listOfRows();
	}

	private List<Map<String, Object>> tracesOfShop(String shop) {
		return this.platform.jdbc().sql("""
				select action, severity, company_id, link_id, external_shop_id, actor_type, actor_id, actor_name, reason,
				  details::text as details
				from platform.integration_audit_log where external_shop_id = :shop and occurred_at >= :since
				order by occurred_at, id""").param("shop", shop).param("since", this.startedAt).query().listOfRows();
	}

	private JsonNode details(Map<String, Object> trace) {
		return this.json.readTree((String) trace.get("details"));
	}

	private void assertUser(Map<String, Object> trace, User user) {
		assertThat(trace.get("actor_type")).isEqualTo("USER");
		assertThat(trace.get("actor_id")).isEqualTo(user.id());
		assertThat(trace.get("actor_name")).isEqualTo(user.username());
	}

	/** Journal de l'entreprise de la personne, lu par l'API d'audit existante. */
	private List<JsonNode> companyAudit(User reader, UUID link) throws Exception {
		return items(this.mvc.perform(bearer(get("/api/audit-logs").param("entityId", link.toString()).param("size", "100"),
				token(reader))).andExpect(status().isOk()));
	}

	private List<JsonNode> companyAuditSearch(User reader, String text) throws Exception {
		return items(this.mvc.perform(bearer(get("/api/audit-logs").param("entityTypes", "INTEGRATION_LINK")
			.param("search", text).param("size", "100"), token(reader))).andExpect(status().isOk()));
	}

	private List<JsonNode> items(ResultActions result) throws Exception {
		List<JsonNode> items = new ArrayList<>();
		this.json.readTree(result.andReturn().getResponse().getContentAsString()).get("items").forEach(items::add);
		return items;
	}

	private MockHttpServletRequestBuilder jsonPost(String path, Map<String, Object> body) {
		return post(path).contentType(MediaType.APPLICATION_JSON).content(this.json.writeValueAsString(body));
	}

	private static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder request, String token) {
		return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
	}

	private String clientBasic() {
		return basic(this.client.clientId(), this.clientSecret);
	}

	private String basic(String clientId, String secret) {
		return "Basic " + remember(Base64.getEncoder().encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.UTF_8)));
	}

	private String verifier() {
		return remember("verificateur-g12-" + UUID.randomUUID() + "-" + UUID.randomUUID());
	}

	private static String challenge(String verifier) throws Exception {
		return Base64.getUrlEncoder().withoutPadding()
			.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
	}

	private String remember(String value) {
		this.sensitive.add(value);
		return value;
	}

	private static List<String> concat(List<String> a, List<String> b) {
		List<String> all = new ArrayList<>(a);
		all.addAll(b);
		return all;
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
		String code = "it-g12-" + UUID.randomUUID().toString().substring(0, 8);
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

	private void dropCompanyB() throws Exception {
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

}
