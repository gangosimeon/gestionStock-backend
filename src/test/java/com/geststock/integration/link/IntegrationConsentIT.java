package com.geststock.integration.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.integration.ChangeFeedService;
import com.geststock.integration.LinkWarehouses;
import com.geststock.platform.PlatformDb;
import com.geststock.platform.provisioning.ProvisionerAccess;
import com.geststock.security.JwtTokenService;
import com.geststock.stock.StockFixtures;
import com.geststock.stock.StockMovementCommand;
import com.geststock.stock.StockMovementType;
import com.geststock.stock.StockService;
import com.geststock.stock.StockSourceType;
import com.geststock.support.PostgresTestConfiguration;
import com.geststock.tenancy.TenantContext;
import com.geststock.tenancy.TenantDataSourceRegistry;
import com.geststock.tenancy.TenantRef;
import com.geststock.user.UserAccountService;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consentement et gestion des liaisons côté GestStock (lot 2, étape G10 ; v14-D1 étapes 2 et 3, D2, D7, D10, D14) :
 * routes de la chaîne principale, permission {@code integration:manage}, sur deux entreprises réelles (A : entreprise de
 * test historique ; B : provisionnée pour ce test) et de vrais jetons d'utilisateurs ({@code tid}). Le parcours complet
 * passe par {@code /oauth/par} et {@code /oauth/token} (G8). Aucun secret, code ni {@code request_uri} dans les
 * journaux, y compris en TRACE.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "geststock.platform.provisioning.async=false")
@Import({ PostgresTestConfiguration.class, IntegrationConsentIT.ConsentTestController.class })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IntegrationConsentIT {

	private static final String SUPER_ADMIN = "it_g10_superadmin";

	private static final String SUPER_ADMIN_PASSWORD = "Plateforme-Test-2026";

	private static final String REQUESTS = "/api/integration-links/authorization-requests";

	private static final String LINKS = "/api/integration-links";

	private static final String CATALOG = "/api/integration/v1/test-consent/catalog";

	private static final Set<String> ALLOWED = Set.of("catalog:read", "orders:read", "orders:write");

	private static final SecureRandom RANDOM = new SecureRandom();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private PlatformDb platform;

	/** Espion : échec forcé de l'écriture sur la base plateforme (G4a). */
	@MockitoSpyBean
	private LinkRegistry registry;

	/** Espion : échec forcé de l'écriture sur la base de l'entreprise (G4a). */
	@MockitoSpyBean
	private LinkWarehouses linkWarehouses;

	@Autowired
	private StockService stock;

	@Autowired
	private ChangeFeedService feed;

	@Autowired
	private TransactionTemplate transactions;

	private StockFixtures stockFixtures;

	@Autowired
	private JdbcTemplate jdbc;

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

	private User otherAdminA;

	private User cashierA;

	private User adminB;

	/** {@code integration:manage} sans {@code warehouse:all}, affecté au seul dépôt {@link #storeA}. */
	private User managerA;

	private UUID mainA;

	private UUID storeA;

	private UUID otherStoreA;

	private UUID depotA;

	private UUID closedA;

	private UUID warehouseB;

	private UUID managerRole;

	private final List<UUID> createdCompanies = new ArrayList<>();

	private final List<String> sensitive = java.util.Collections.synchronizedList(new ArrayList<>());

	private final Map<Logger, Level> levels = new LinkedHashMap<>();

	private ListAppender<ILoggingEvent> logs;

	private String clientSecret;

	private TestLinks.Client client;

	@BeforeAll
	void provision() throws Exception {
		this.links = new TestLinks(this.platform, this.registry);
		this.companyA = new TenantRef(this.links.legacyCompany(), "test");
		this.companyB = provisionCompanyB();
		this.adminA = user(this.companyA, "ADMIN");
		this.otherAdminA = user(this.companyA, "ADMIN");
		this.cashierA = user(this.companyA, "CAISSIER");
		this.adminB = user(this.companyB, "ADMIN");
		String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
		TenantContext.runAs(this.companyA, () -> {
			this.mainA = this.jdbc.queryForObject("select id from warehouse where main", UUID.class);
			this.storeA = warehouse("G4A-S1-" + suffix, "STORE", true);
			this.otherStoreA = warehouse("G4A-S2-" + suffix, "STORE", true);
			this.depotA = warehouse("G4A-D-" + suffix, "WAREHOUSE", true);
			this.closedA = warehouse("G4A-X-" + suffix, "STORE", false);
			this.managerRole = this.jdbc.queryForObject(
					"insert into role (code, label) values (?, 'Liaisons boutique (essai G4a)') returning id", UUID.class,
					"G4A_LINKS_" + suffix);
			this.jdbc.update("insert into role_permission (role_id, permission_id) select ?, id from permission where code = ?",
					this.managerRole, "integration:manage");
		});
		this.managerA = user(this.companyA, "G4A_LINKS_" + suffix);
		TenantContext.runAs(this.companyA, () -> this.jdbc.update(
				"insert into user_warehouse (user_id, warehouse_id) values (?, ?)", this.managerA.id(), this.storeA));
		this.warehouseB = TenantContext.callAs(this.companyB, () -> warehouse("G4A-B-" + suffix, "STORE", true));
	}

	@BeforeEach
	void setUp() {
		this.clientSecret = remember(randomToken());
		this.client = this.links.client(this.clientSecret, null, ALLOWED);
		this.stockFixtures = new StockFixtures(this.jdbc, this.transactions);
		LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
		for (Logger logger : context.getLoggerList()) {
			if (logger.getLevel() != null) {
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
		List<String> messages = this.logs.list.stream().map(IntegrationConsentIT::render).toList();
		try {
			assertThat(this.logs.list).as("journaux capturés en TRACE").anyMatch(event -> event.getLevel() == Level.TRACE);
			assertThat(messages).as("aucun secret, code, request_uri ni jeton dans les journaux")
				.allSatisfy(message -> this.sensitive.forEach(value -> assertThat(message).doesNotContain(value)));
		}
		finally {
			this.sensitive.clear();
			this.platform.jdbc().sql("update platform.company set currency = 'FCFA' where id = :id")
				.param("id", this.companyA.companyId()).update();
			this.links.cleanUp();
			this.stockFixtures.purge();
		}
	}

	@AfterAll
	void dropCompanyB() throws Exception {
		TenantContext.runAs(this.companyA, () -> {
			this.jdbc.update("delete from app_user where id in (?, ?, ?, ?)", this.adminA.id(), this.otherAdminA.id(),
					this.cashierA.id(), this.managerA.id());
			this.jdbc.update("delete from role where id = ?", this.managerRole);
			this.jdbc.update("delete from warehouse where id in (?, ?, ?, ?)", this.storeA, this.otherStoreA, this.depotA,
					this.closedA);
		});
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

	// --- Permission ---------------------------------------------------------------------------------------

	@Test
	void everyRouteRequiresIntegrationManage() throws Exception {
		String requestUri = par("shop-g10-perm", verifier());
		UUID link = this.links.active(this.client, this.companyA.companyId(), "shop-g10-perm-link", Set.of("catalog:read")).id();
		String token = token(this.cashierA);
		List<MockHttpServletRequestBuilder> routes = List.of(jsonPost(REQUESTS + "/lookup", Map.of("requestUri", requestUri)),
				jsonPost(REQUESTS + "/approve",
						Map.of("requestUri", requestUri, "scopes", List.of("catalog:read"), "warehouseIds", List.of(this.mainA))),
				jsonPost(REQUESTS + "/deny", Map.of("requestUri", requestUri)), get(LINKS),
				post(LINKS + "/{id}/suspend", link), reactivation(link, List.of(this.mainA)), post(LINKS + "/{id}/revoke", link));
		for (MockHttpServletRequestBuilder route : routes) {
			this.mvc.perform(bearer(route, token)).andExpect(status().isForbidden());
		}
		// Rien n'a été décidé ni modifié.
		lookup(this.adminA, requestUri).andExpect(status().isOk());
		assertThat(this.registry.find(link).orElseThrow().status()).isEqualTo(LinkStatus.ACTIVE);
		this.mvc.perform(bearer(get(LINKS), token(this.adminA))).andExpect(status().isOk());
	}

	// --- Parcours complet ---------------------------------------------------------------------------------

	@Test
	void theWholeConsentFlowActivatesALinkOfTheApproversCompany() throws Exception {
		String verifier = verifier();
		String state = randomToken() + "+&=/état";
		String requestUri = par("shop-g10-flow", verifier, state, "catalog:read orders:read");

		MockHttpServletResponse looked = lookup(this.adminA, requestUri).andExpect(status().isOk())
			.andExpect(jsonPath("$.shopName").value("Boutique shop-g10-flow"))
			.andExpect(jsonPath("$.shopId").value("shop-g10-flow"))
			.andExpect(jsonPath("$.clientId").value(this.client.clientId()))
			.andExpect(jsonPath("$.clientName").value("JeCréeMaBoutique (essai)"))
			.andExpect(jsonPath("$.scopes[0]").value("catalog:read"))
			.andExpect(jsonPath("$.scopes[1]").value("orders:read"))
			.andExpect(jsonPath("$.currency").value("XOF"))
			.andExpect(jsonPath("$.expiresAt").isNotEmpty())
			.andReturn().getResponse();
		assertThat(looked.getContentAsString()).doesNotContain(requestUri).doesNotContain(LinkRegistry.sha256Hex(requestUri));

		JsonNode approved = read(approve(this.adminA, requestUri, List.of("catalog:read")).andExpect(status().isOk()));
		URI redirect = URI.create(approved.get("redirectUrl").asString());
		assertThat(redirect.getScheme() + "://" + redirect.getHost() + redirect.getPath()).isEqualTo(TestLinks.REDIRECT_URI);
		Map<String, String> query = query(redirect);
		assertThat(query.keySet()).containsExactlyInAnyOrder("code", "state", "iss");
		String code = remember(query.get("code"));
		assertThat(query.get("state")).isEqualTo(state);
		assertThat(query.get("iss")).isEqualTo(IntegrationTokenService.ISSUER);

		// Liaison en attente, de l'entreprise de la personne qui approuve, à son nom.
		Link pending = linkOfRequest(requestUri);
		assertThat(pending.status()).isEqualTo(LinkStatus.PENDING);
		assertThat(pending.companyId()).isEqualTo(this.companyA.companyId());
		assertThat(pending.authorizedBy()).isEqualTo(this.adminA.id());
		assertThat(pending.authorizedByUsername()).isEqualTo(this.adminA.username());
		assertThat(pending.scopes()).containsExactly("catalog:read");

		// Échange du code (G8) : liaison ACTIVE.
		JsonNode token = read(this.mvc.perform(post("/api/integration/v1/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.header(HttpHeaders.AUTHORIZATION, basic(this.client.clientId(), this.clientSecret))
			.param("grant_type", "authorization_code").param("code", code).param("redirect_uri", TestLinks.REDIRECT_URI)
			.param("code_verifier", verifier)).andExpect(status().isOk()));
		remember(token.get("access_token").asString());
		remember(token.get("link_secret").asString());
		assertThat(token.get("link").get("id").asString()).isEqualTo(pending.id().toString());
		assertThat(this.registry.find(pending.id()).orElseThrow().status()).isEqualTo(LinkStatus.ACTIVE);

		// Liste de l'entreprise : la liaison, sans empreinte.
		String list = this.mvc.perform(bearer(get(LINKS), token(this.adminA))).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		JsonNode mine = find(this.json.readTree(list), pending.id());
		assertThat(mine).as("liaison listée").isNotNull();
		assertThat(mine.get("status").asString()).isEqualTo("ACTIVE");
		assertThat(mine.get("authorizedBy").asString()).isEqualTo(this.adminA.username());
		assertThat(list).doesNotContainIgnoringCase("hash").doesNotContain(token.get("link_secret").asString());
	}

	@Test
	void aDenialRedirectsWithAccessDeniedAndCreatesNothing() throws Exception {
		String state = randomToken();
		String requestUri = par("shop-g10-deny", verifier(), state, "catalog:read");

		JsonNode denied = read(deny(this.adminA, requestUri).andExpect(status().isOk()));
		URI redirect = URI.create(denied.get("redirectUrl").asString());
		assertThat(redirect.getScheme() + "://" + redirect.getHost() + redirect.getPath()).isEqualTo(TestLinks.REDIRECT_URI);
		Map<String, String> query = query(redirect);
		assertThat(query.keySet()).containsExactlyInAnyOrder("error", "state", "iss");
		assertThat(query.get("error")).isEqualTo("access_denied");
		assertThat(query.get("state")).isEqualTo(state);
		assertThat(query.get("iss")).isEqualTo(IntegrationTokenService.ISSUER);
		assertThat(decision(requestUri)).isEqualTo("DENIED");
		assertThat(linksOfRequestShop("shop-g10-deny")).isZero();
		assertThat(codesOfRequest(requestUri)).isZero();

		// La demande ne sert qu'à une décision.
		approve(this.adminA, requestUri, List.of("catalog:read")).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("AUTHORIZATION_REQUEST_ALREADY_DECIDED"));
		deny(this.adminA, requestUri).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("AUTHORIZATION_REQUEST_ALREADY_DECIDED"));
		lookup(this.adminA, requestUri).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("AUTHORIZATION_REQUEST_ALREADY_DECIDED"));
		assertThat(linksOfRequestShop("shop-g10-deny")).isZero();
	}

	// --- Demandes refusées ----------------------------------------------------------------------------------

	@Test
	void anUnknownExpiredOrDecidedRequestGetsItsOwnCode() throws Exception {
		String unknown = remember("urn:ietf:params:oauth:request_uri:" + randomToken());
		lookup(this.adminA, unknown).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("AUTHORIZATION_REQUEST_NOT_FOUND"));
		approve(this.adminA, unknown, List.of("catalog:read")).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("AUTHORIZATION_REQUEST_NOT_FOUND"));
		deny(this.adminA, unknown).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("AUTHORIZATION_REQUEST_NOT_FOUND"));

		String expired = par("shop-g10-expired", verifier());
		this.platform.jdbc().sql("""
				update platform.integration_authorization_request
				set created_at = now() - interval '20 minutes', expires_at = now() - interval '10 minutes'
				where request_uri_hash = :hash""").param("hash", LinkRegistry.sha256Hex(expired)).update();
		lookup(this.adminA, expired).andExpect(status().isGone())
			.andExpect(jsonPath("$.code").value("AUTHORIZATION_REQUEST_EXPIRED"));
		approve(this.adminA, expired, List.of("catalog:read")).andExpect(status().isGone())
			.andExpect(jsonPath("$.code").value("AUTHORIZATION_REQUEST_EXPIRED"));
		deny(this.adminA, expired).andExpect(status().isGone())
			.andExpect(jsonPath("$.code").value("AUTHORIZATION_REQUEST_EXPIRED"));
		assertThat(decision(expired)).isNull();
		assertThat(linksOfRequestShop("shop-g10-expired")).isZero();

		String decided = par("shop-g10-decided", verifier());
		approve(this.adminA, decided, List.of("catalog:read")).andExpect(status().isOk());
		approve(this.otherAdminA, decided, List.of("catalog:read")).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("AUTHORIZATION_REQUEST_ALREADY_DECIDED"));
		assertThat(linksOfRequestShop("shop-g10-decided")).isEqualTo(1);
	}

	@Test
	void scopesOutsideTheRequestAreRefusedAndNothingIsDecided() throws Exception {
		String requestUri = par("shop-g10-scopes", verifier(), randomToken(), "catalog:read");
		approve(this.adminA, requestUri, List.of("catalog:read", "orders:write")).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("LINK_SCOPE_NOT_REQUESTED"));
		approve(this.adminA, requestUri, List.of()).andExpect(status().isBadRequest());
		assertThat(decision(requestUri)).isNull();
		assertThat(linksOfRequestShop("shop-g10-scopes")).isZero();
		approve(this.adminA, requestUri, List.of("catalog:read")).andExpect(status().isOk());
	}

	@Test
	void anUnsupportedCurrencyIsRefusedAtLookupAndApproval() throws Exception {
		String requestUri = par("shop-g10-currency", verifier());
		this.platform.jdbc().sql("update platform.company set currency = 'PIECES' where id = :id")
			.param("id", this.companyA.companyId()).update();
		lookup(this.adminA, requestUri).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("LINK_CURRENCY_UNSUPPORTED"));
		approve(this.adminA, requestUri, List.of("catalog:read")).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("LINK_CURRENCY_UNSUPPORTED"));
		assertThat(decision(requestUri)).isNull();
		assertThat(linksOfRequestShop("shop-g10-currency")).isZero();
		assertThat(codesOfRequest(requestUri)).isZero();
	}

	@Test
	void twoSimultaneousApprovalsOfTheSameRequestLetOnlyOneThrough() throws Exception {
		String requestUri = par("shop-g10-race", verifier());
		int calls = 6;
		String tokenA = token(this.adminA);
		String tokenOther = token(this.otherAdminA);
		CyclicBarrier start = new CyclicBarrier(calls);
		ExecutorService pool = Executors.newFixedThreadPool(calls);
		try {
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < calls; i++) {
				MockHttpServletRequestBuilder request = bearer(
						jsonPost(REQUESTS + "/approve", Map.of("requestUri", requestUri, "scopes", List.of("catalog:read"),
								"warehouseIds", List.of(this.mainA))),
						i % 2 == 0 ? tokenA : tokenOther);
				results.add(pool.submit(() -> {
					start.await(10, TimeUnit.SECONDS);
					MockHttpServletResponse response = this.mvc.perform(request).andReturn().getResponse();
					if (response.getStatus() == 200) {
						remember(code(this.json.readTree(response.getContentAsString())));
					}
					return response.getStatus();
				}));
			}
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> result : results) {
				statuses.add(result.get(30, TimeUnit.SECONDS));
			}
			assertThat(statuses).filteredOn(s -> s == 200).hasSize(1);
			assertThat(statuses).filteredOn(s -> s == 409).hasSize(calls - 1);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(linksOfRequestShop("shop-g10-race")).isEqualTo(1);
		assertThat(codesOfRequest(requestUri)).isEqualTo(1);
	}

	@Test
	void aShopAlreadyLinkedToAnotherCompanyIsRefusedExplicitly() throws Exception {
		String requestUri = par("shop-g10-taken", verifier());
		// Entre le dépôt et l'approbation, la boutique est liée à l'entreprise B.
		this.links.pending(this.client, this.companyB.companyId(), "shop-g10-taken", Set.of("catalog:read"));

		approve(this.adminA, requestUri, List.of("catalog:read")).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SHOP_ALREADY_LINKED"))
			.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(this.companyB.code()))));
		assertThat(this.platform.jdbc().sql("""
				select count(*) from platform.integration_link where external_shop_id = 'shop-g10-taken' and company_id = :a""")
			.param("a", this.companyA.companyId()).query(Integer.class).single()).isZero();
		assertThat(decision(requestUri)).isNull();
		assertThat(codesOfRequest(requestUri)).isZero();
	}

	// --- Gestion des liaisons -------------------------------------------------------------------------------

	@Test
	void anotherCompanyNeitherSeesNorManagesTheLinksOfA() throws Exception {
		Link linkOfA = this.links.active(this.client, this.companyA.companyId(), "shop-g10-isolation", Set.of("catalog:read"));
		String tokenB = token(this.adminB);

		String listB = this.mvc.perform(bearer(get(LINKS), tokenB)).andExpect(status().isOk()).andReturn().getResponse()
			.getContentAsString();
		assertThat(listB).doesNotContain(linkOfA.id().toString());
		for (String action : List.of("suspend", "reactivate", "revoke")) {
			String foreign = this.mvc.perform(bearer(action(action, linkOfA.id(), this.warehouseB), tokenB))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"))
				.andReturn().getResponse().getContentAsString();
			String unknown = this.mvc.perform(bearer(action(action, UUID.randomUUID(), this.warehouseB), tokenB))
				.andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
			assertThat(withoutVolatileFields(foreign)).as(action + " : même réponse qu'une liaison inexistante")
				.isEqualTo(withoutVolatileFields(unknown));
		}
		assertThat(this.registry.find(linkOfA.id()).orElseThrow().status()).isEqualTo(LinkStatus.ACTIVE);
	}

	@Test
	void suspensionReactivationAndRevocationTakeEffectImmediatelyOnTheIntegrationChain() throws Exception {
		ActivatedLink activated = this.registry.activate(
				this.links.pending(this.client, this.companyA.companyId(), "shop-g10-manage", Set.of("catalog:read")).id());
		remember(activated.secret());
		UUID id = activated.link().id();
		String linkToken = remember(read(this.mvc.perform(post("/api/integration/v1/oauth/token")
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.header(HttpHeaders.AUTHORIZATION, basic(this.client.clientId(), this.clientSecret))
			.param("grant_type", "client_credentials").param("link_id", id.toString()).param("link_secret", activated.secret()))
			.andExpect(status().isOk())).get("access_token").asString());
		this.mvc.perform(bearer(get(CATALOG), linkToken)).andExpect(status().isOk());

		this.mvc.perform(bearer(post(LINKS + "/{id}/suspend", id), token(this.adminA))).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SUSPENDED")).andExpect(jsonPath("$.suspensionReason").value("MANUAL"));
		this.mvc.perform(bearer(get(CATALOG), linkToken)).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("LINK_SUSPENDED"));

		this.mvc.perform(bearer(reactivation(id, List.of(this.mainA)), token(this.otherAdminA))).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.authorizedBy").value(this.otherAdminA.username()));
		assertThat(this.registry.find(id).orElseThrow().authorizedBy()).as("responsable mis à jour").isEqualTo(this.otherAdminA.id());
		this.mvc.perform(bearer(get(CATALOG), linkToken)).andExpect(status().isOk());

		this.mvc.perform(bearer(post(LINKS + "/{id}/revoke", id), token(this.adminA))).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REVOKED")).andExpect(jsonPath("$.revocationReason").value("GESTSTOCK_ADMIN"));
		this.mvc.perform(bearer(get(CATALOG), linkToken)).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("LINK_REVOKED"));

		this.mvc.perform(bearer(post(LINKS + "/{id}/suspend", id), token(this.adminA))).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("LINK_TRANSITION_INVALID"));
	}

	// --- Dépôts vendables (G4a) -----------------------------------------------------------------------------

	@Test
	void theLookupListsTheActiveWarehousesAccessibleToThePersonWithTheirType() throws Exception {
		String requestUri = par("shop-g4a-lookup", verifier());

		JsonNode admin = read(lookup(this.adminA, requestUri).andExpect(status().isOk()));
		Map<UUID, String> adminWarehouses = warehouseTypes(admin);
		assertThat(adminWarehouses).containsEntry(this.storeA, "STORE").containsEntry(this.otherStoreA, "STORE")
			.containsEntry(this.depotA, "WAREHOUSE").containsKey(this.mainA).doesNotContainKey(this.closedA)
			.doesNotContainKey(this.warehouseB);

		JsonNode manager = read(lookup(this.managerA, requestUri).andExpect(status().isOk()));
		assertThat(warehouseTypes(manager)).as("dépôts affectés seulement").containsOnlyKeys(this.storeA);
	}

	@Test
	void theApprovedWarehousesAreRecordedAndListedWithTheLink() throws Exception {
		String requestUri = par("shop-g4a-approve", verifier());
		approve(this.adminA, requestUri, List.of("catalog:read"), List.of(this.storeA, this.depotA)).andExpect(status().isOk());

		Link link = linkOfRequest(requestUri);
		assertThat(warehousesOf(link.id())).containsExactlyInAnyOrder(this.storeA, this.depotA);
		assertThat(TenantContext.callAs(this.companyA, () -> this.jdbc.queryForList(
				"select distinct added_by from integration_link_warehouse where link_id = ?", UUID.class, link.id())))
			.containsExactly(this.adminA.id());

		JsonNode listed = find(read(this.mvc.perform(bearer(get(LINKS), token(this.adminA))).andExpect(status().isOk())), link.id());
		assertThat(warehouseTypes(listed)).containsOnlyKeys(this.storeA, this.depotA).containsEntry(this.depotA, "WAREHOUSE");
	}

	@Test
	void aWarehouseThatCannotBeChosenIsRefusedAndNothingIsWrittenAnywhere() throws Exception {
		String requestUri = par("shop-g4a-refused", verifier());
		int rows = linkWarehouseRows();

		approve(this.managerA, requestUri, List.of("catalog:read"), List.of(this.storeA, this.otherStoreA))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("WAREHOUSE_ACCESS_DENIED"));
		approve(this.adminA, requestUri, List.of("catalog:read"), List.of(this.storeA, this.closedA))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("WAREHOUSE_INACTIVE"));
		String unknown = approve(this.adminA, requestUri, List.of("catalog:read"), List.of(UUID.randomUUID()))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_WAREHOUSE"))
			.andReturn().getResponse().getContentAsString();
		String foreign = approve(this.adminA, requestUri, List.of("catalog:read"), List.of(this.warehouseB))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_WAREHOUSE"))
			.andReturn().getResponse().getContentAsString();
		assertThat(withoutVolatileFields(foreign)).as("dépôt d'une autre entreprise : même réponse qu'un dépôt inexistant")
			.isEqualTo(withoutVolatileFields(unknown));
		approve(this.adminA, requestUri, List.of("catalog:read"), List.of()).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		this.mvc.perform(bearer(jsonPost(REQUESTS + "/approve", Map.of("requestUri", requestUri, "scopes", List.of("catalog:read"))),
				token(this.adminA))).andExpect(status().isBadRequest());

		assertThat(decision(requestUri)).isNull();
		assertThat(linksOfRequestShop("shop-g4a-refused")).isZero();
		assertThat(codesOfRequest(requestUri)).isZero();
		assertThat(linkWarehouseRows()).as("aucune ligne écrite dans la base de l'entreprise").isEqualTo(rows);
	}

	@Test
	void theReactivationReplacesTheWarehousesWithTheReconfirmedOnes() throws Exception {
		UUID link = suspendedLinkWith("shop-g4a-reactivate", this.storeA, this.depotA);

		// Refus : rien ne change.
		this.mvc.perform(bearer(reactivation(link, List.of(this.otherStoreA)), token(this.managerA)))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("WAREHOUSE_ACCESS_DENIED"));
		this.mvc.perform(bearer(reactivation(link, List.of()), token(this.adminA))).andExpect(status().isBadRequest());
		assertThat(warehousesOf(link)).containsExactlyInAnyOrder(this.storeA, this.depotA);
		assertThat(this.registry.find(link).orElseThrow().status()).isEqualTo(LinkStatus.SUSPENDED);

		this.mvc.perform(bearer(reactivation(link, List.of(this.otherStoreA)), token(this.otherAdminA))).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.warehouses[0].id").value(this.otherStoreA.toString()));
		assertThat(warehousesOf(link)).as("ancienne liste remplacée").containsExactly(this.otherStoreA);
		assertThat(TenantContext.callAs(this.companyA, () -> this.jdbc.queryForList(
				"select distinct added_by from integration_link_warehouse where link_id = ?", UUID.class, link)))
			.containsExactly(this.otherAdminA.id());

		// Une liaison active n'est pas réactivée : ses dépôts ne sont pas touchés.
		this.mvc.perform(bearer(reactivation(link, List.of(this.storeA)), token(this.adminA))).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("LINK_TRANSITION_INVALID"));
		assertThat(warehousesOf(link)).containsExactly(this.otherStoreA);
	}

	// --- Deux bases, aucune transaction commune : échecs forcés à chaque étape ----------------------------------

	/** Approbation, étape 1 (base de l'entreprise) en échec : rien n'est écrit nulle part. */
	@Test
	void approvalFailingOnTheCompanyDatabaseWritesNothing() throws Exception {
		String requestUri = par("shop-g4a-fail-1", verifier());
		int rows = linkWarehouseRows();
		doThrow(new IllegalStateException("panne simulée de la base de l'entreprise")).when(this.linkWarehouses)
			.replace(any(UUID.class), anySet(), any(UUID.class));

		approve(this.adminA, requestUri, List.of("catalog:read"), List.of(this.storeA)).andExpect(status().isInternalServerError());

		assertThat(decision(requestUri)).isNull();
		assertThat(linksOfRequestShop("shop-g4a-fail-1")).isZero();
		assertThat(codesOfRequest(requestUri)).isZero();
		assertThat(linkWarehouseRows()).isEqualTo(rows);
	}

	/**
	 * Approbation, étape 2 (base plateforme) en échec après l'étape 1 : décision, liaison et code annulés ensemble, puis
	 * étape 3 : les dépôts écrits en 1 sont retirés.
	 */
	@Test
	void approvalFailingOnThePlatformRemovesTheWarehousesWrittenFirst() throws Exception {
		String requestUri = par("shop-g4a-fail-2", verifier());
		int rows = linkWarehouseRows();
		doThrow(new IllegalStateException("panne simulée de la base plateforme")).when(this.registry)
			.createPending(any(UUID.class), any(UUID.class), any(UUID.class), any(Authorizer.class), anySet());

		approve(this.adminA, requestUri, List.of("catalog:read"), List.of(this.storeA)).andExpect(status().isInternalServerError());

		assertThat(decision(requestUri)).isNull();
		assertThat(linksOfRequestShop("shop-g4a-fail-2")).isZero();
		assertThat(codesOfRequest(requestUri)).isZero();
		assertThat(linkWarehouseRows()).as("dépôts retirés (compensation)").isEqualTo(rows);
	}

	/**
	 * Étapes 2 et 3 en échec : restent des lignes de dépôts pour un identifiant de liaison qui n'existe pas, donc
	 * qu'aucun jeton ne peut porter : sans effet.
	 */
	@Test
	void approvalFailingOnThePlatformAndOnTheCleanupLeavesOnlyHarmlessOrphanRows() throws Exception {
		String requestUri = par("shop-g4a-fail-3", verifier());
		int rows = linkWarehouseRows();
		doThrow(new IllegalStateException("panne simulée de la base plateforme")).when(this.registry)
			.createPending(any(UUID.class), any(UUID.class), any(UUID.class), any(Authorizer.class), anySet());
		doThrow(new IllegalStateException("panne simulée de la base de l'entreprise")).when(this.linkWarehouses)
			.remove(any(UUID.class));

		approve(this.adminA, requestUri, List.of("catalog:read"), List.of(this.storeA)).andExpect(status().isInternalServerError());

		assertThat(decision(requestUri)).isNull();
		assertThat(linksOfRequestShop("shop-g4a-fail-3")).isZero();
		assertThat(codesOfRequest(requestUri)).isZero();
		List<UUID> orphans = TenantContext.callAs(this.companyA, () -> this.jdbc.queryForList("""
				select distinct link_id from integration_link_warehouse where added_by = ? and warehouse_id = ?
				  and added_at > now() - interval '1 minute'""", UUID.class, this.adminA.id(), this.storeA));
		try {
			assertThat(linkWarehouseRows()).as("lignes orphelines").isEqualTo(rows + 1);
			assertThat(orphans).isNotEmpty().allSatisfy(orphan -> assertThat(this.registry.find(orphan))
				.as("liaison %s inexistante : lignes sans effet", orphan).isEmpty());
		}
		finally {
			TenantContext.runAs(this.companyA, () -> orphans.forEach(orphan -> {
				if (this.registry.find(orphan).isEmpty()) {
					this.jdbc.update("delete from integration_link_warehouse where link_id = ?", orphan);
				}
			}));
		}
	}

	/**
	 * Réactivation, étape 1 (base de l'entreprise) en échec après la publication des événements, dans la même
	 * transaction : ancienne liste intacte, aucun événement, liaison toujours suspendue.
	 */
	@Test
	void reactivationFailingOnTheCompanyDatabaseChangesNothingAndPublishesNothing() throws Exception {
		UUID product = stocked(this.storeA, 2);
		UUID link = suspendedLinkWith("shop-g4a-fail-r1", this.storeA);
		int before = availabilityEvents(product);
		doAnswer(invocation -> {
			invocation.callRealMethod();
			throw new IllegalStateException("panne simulée de la base de l'entreprise, après la publication");
		}).when(this.linkWarehouses).republish(anySet());

		this.mvc.perform(bearer(reactivation(link, List.of(this.otherStoreA)), token(this.adminA)))
			.andExpect(status().isInternalServerError());

		assertThat(warehousesOf(link)).containsExactly(this.storeA);
		assertThat(availabilityEvents(product)).as("événements annulés avec la transaction").isEqualTo(before);
		assertThat(this.registry.find(link).orElseThrow().status()).isEqualTo(LinkStatus.SUSPENDED);
	}

	/**
	 * Réactivation, étape 2 (base plateforme) en échec après l'étape 1 : la liste reconfirmée est en place mais la
	 * liaison reste suspendue ({@code 403 LINK_SUSPENDED}) : aucune disponibilité servie ; une nouvelle réactivation
	 * remplacera la liste.
	 */
	@Test
	void reactivationFailingOnThePlatformLeavesTheLinkSuspended() throws Exception {
		UUID link = suspendedLinkWith("shop-g4a-fail-r2", this.storeA);
		doThrow(new IllegalStateException("panne simulée de la base plateforme")).when(this.registry)
			.reactivate(any(UUID.class), any(Authorizer.class));

		this.mvc.perform(bearer(reactivation(link, List.of(this.otherStoreA)), token(this.adminA)))
			.andExpect(status().isInternalServerError());

		assertThat(this.registry.find(link).orElseThrow().status()).isEqualTo(LinkStatus.SUSPENDED);
		assertThat(warehousesOf(link)).as("liste reconfirmée, jamais plus large").containsExactly(this.otherStoreA);
	}

	// --- Changement de périmètre : disponibilité republiée (v11-D9) ---------------------------------------------

	/**
	 * Réactivation avec une autre liste : un événement AVAILABILITY par produit stocké dans l'ancienne ou la nouvelle
	 * liste (union), dans la transaction du remplacement ; la valeur servie ensuite est celle des nouveaux dépôts.
	 */
	@Test
	void aReactivationWithOtherWarehousesRepublishesTheAvailabilityOfTheirProducts() throws Exception {
		UUID inOld = stocked(this.storeA, 3);
		UUID inNew = stocked(this.otherStoreA, 5);
		UUID inBoth = stocked(this.depotA, 2);
		UUID elsewhere = stocked(this.mainA, 1);
		UUID link = suspendedLinkWith("shop-g4a-scope", this.storeA, this.depotA);
		long after = lastSeq();
		Map<UUID, Integer> before = Map.of(inOld, availabilityEvents(inOld), inNew, availabilityEvents(inNew), inBoth,
				availabilityEvents(inBoth), elsewhere, availabilityEvents(elsewhere));

		this.mvc.perform(bearer(reactivation(link, List.of(this.otherStoreA, this.depotA)), token(this.adminA)))
			.andExpect(status().isOk());

		assertThat(availabilityEvents(inOld)).as("dépôt retiré").isEqualTo(before.get(inOld) + 1);
		assertThat(availabilityEvents(inNew)).as("dépôt ajouté").isEqualTo(before.get(inNew) + 1);
		assertThat(availabilityEvents(inBoth)).as("dépôt conservé").isEqualTo(before.get(inBoth) + 1);
		assertThat(availabilityEvents(elsewhere)).as("hors des deux listes").isEqualTo(before.get(elsewhere));

		Map<UUID, JsonNode> served = servedAvailability(new com.geststock.integration.LinkRef(link), after);
		assertThat(served.get(inOld).get("available").asInt()).isZero();
		assertThat(served.get(inNew).get("available").asInt()).isEqualTo(5);
		assertThat(served.get(inBoth).get("available").asInt()).isEqualTo(2);
		assertThat(served).doesNotContainKey(elsewhere);
	}

	/** Liste identique : le périmètre ne change pas, aucun événement n'est publié. */
	@Test
	void aReactivationWithTheSameWarehousesPublishesNothing() throws Exception {
		UUID product = stocked(this.storeA, 4);
		UUID link = suspendedLinkWith("shop-g4a-same", this.storeA);
		int before = availabilityEvents(product);

		this.mvc.perform(bearer(reactivation(link, List.of(this.storeA)), token(this.otherAdminA))).andExpect(status().isOk());

		assertThat(availabilityEvents(product)).isEqualTo(before);
		assertThat(TenantContext.callAs(this.companyA, () -> this.jdbc.queryForList(
				"select distinct added_by from integration_link_warehouse where link_id = ?", UUID.class, link)))
			.as("reconfirmée par la personne qui réactive").containsExactly(this.otherAdminA.id());
	}

	private UUID stocked(UUID warehouse, int quantity) {
		UUID product = this.stockFixtures.product("g4a", 1000);
		UUID actor = this.stockFixtures.user("g4a");
		StockFixtures.runAs(actor, () -> this.stock.applyMovement(StockMovementCommand.of(warehouse, product,
				StockMovementType.RECEIPT, quantity, StockSourceType.MANUAL, "Stock G4a")));
		return product;
	}

	private int availabilityEvents(UUID product) {
		return TenantContext.callAs(this.companyA, () -> this.jdbc.queryForObject(
				"select count(*) from integration_outbox where type = 'AVAILABILITY' and object_id = ?", Integer.class, product));
	}

	private long lastSeq() {
		return TenantContext.callAs(this.companyA, () -> {
			this.feed.read(new com.geststock.integration.LinkRef(UUID.randomUUID()), 0, 1, com.geststock.integration.ChangeFilter.ALL);
			return this.jdbc.queryForObject("select last_seq from integration_feed_state", Long.class);
		});
	}

	/** Dernière valeur AVAILABILITY servie à la liaison, par produit, après {@code after}. */
	private Map<UUID, JsonNode> servedAvailability(com.geststock.integration.LinkRef link, long after) {
		Map<UUID, JsonNode> values = new LinkedHashMap<>();
		long cursor = after;
		while (true) {
			com.geststock.integration.ChangePage page = this.feed.read(link, cursor, 500,
					com.geststock.integration.ChangeFilter.ALL);
			for (com.geststock.integration.ChangeEvent event : page.items()) {
				if (event.type() == com.geststock.integration.IntegrationEventType.AVAILABILITY) {
					values.put(event.objectId(), event.data());
				}
			}
			cursor = page.lastSeq();
			if (!page.hasMore()) {
				return values;
			}
		}
	}

	/** Liaison de A approuvée par l'API avec ces dépôts, activée puis suspendue. */
	private UUID suspendedLinkWith(String shop, UUID... warehouses) throws Exception {
		String requestUri = par(shop, verifier());
		approve(this.adminA, requestUri, List.of("catalog:read"), List.of(warehouses)).andExpect(status().isOk());
		UUID link = linkOfRequest(requestUri).id();
		remember(this.registry.activate(link).secret());
		this.mvc.perform(bearer(post(LINKS + "/{id}/suspend", link), token(this.adminA))).andExpect(status().isOk());
		return link;
	}

	private static Map<UUID, String> warehouseTypes(JsonNode holder) {
		Map<UUID, String> types = new LinkedHashMap<>();
		holder.get("warehouses").forEach(w -> types.put(UUID.fromString(w.get("id").asString()), w.get("type").asString()));
		return types;
	}

	// --- Outils ---------------------------------------------------------------------------------------------

	private record User(UUID id, String username, TenantRef company) {
	}

	private User user(TenantRef company, String role) {
		String username = "g10." + role.toLowerCase() + "." + UUID.randomUUID().toString().substring(0, 6);
		UUID id = TenantContext.callAs(company, () -> {
			UUID created = this.jdbc.queryForObject(
					"insert into app_user (username, last_name, must_change_password) values (?, 'Essai G10', false) returning id",
					UUID.class, username);
			this.jdbc.update("insert into user_role (user_id, role_id) select ?, id from role where code = ?", created, role);
			return created;
		});
		return new User(id, username, company);
	}

	private String token(User user) {
		return remember(TenantContext.callAs(user.company(),
				() -> this.userTokens.issue(this.accounts.findById(user.id()).orElseThrow()).value()));
	}

	private String par(String shop, String verifier) throws Exception {
		return par(shop, verifier, randomToken(), "catalog:read orders:read");
	}

	private String par(String shop, String verifier, String state, String scopes) throws Exception {
		JsonNode body = read(this.mvc.perform(post("/api/integration/v1/oauth/par").contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.header(HttpHeaders.AUTHORIZATION, basic(this.client.clientId(), this.clientSecret))
			.param("response_type", "code").param("redirect_uri", TestLinks.REDIRECT_URI)
			.param("code_challenge", challenge(verifier)).param("code_challenge_method", "S256").param("scope", scopes)
			.param("state", state).param("shop_id", shop).param("shop_name", "Boutique " + shop))
			.andExpect(status().isCreated()));
		return remember(body.get("request_uri").asString());
	}

	private ResultActions lookup(User user, String requestUri) throws Exception {
		return this.mvc.perform(bearer(jsonPost(REQUESTS + "/lookup", Map.of("requestUri", requestUri)), token(user)));
	}

	private ResultActions approve(User user, String requestUri, List<String> scopes) throws Exception {
		return approve(user, requestUri, scopes, List.of(this.mainA));
	}

	private ResultActions approve(User user, String requestUri, List<String> scopes, List<UUID> warehouses) throws Exception {
		ResultActions result = this.mvc.perform(bearer(jsonPost(REQUESTS + "/approve",
				Map.of("requestUri", requestUri, "scopes", scopes, "warehouseIds", warehouses)), token(user)));
		MockHttpServletResponse response = result.andReturn().getResponse();
		if (response.getStatus() == 200) {
			remember(code(this.json.readTree(response.getContentAsString())));
		}
		return result;
	}

	private ResultActions deny(User user, String requestUri) throws Exception {
		return this.mvc.perform(bearer(jsonPost(REQUESTS + "/deny", Map.of("requestUri", requestUri)), token(user)));
	}

	private MockHttpServletRequestBuilder reactivation(UUID link, List<UUID> warehouses) {
		return jsonPost(LINKS + "/" + link + "/reactivate", Map.of("warehouseIds", warehouses));
	}

	private MockHttpServletRequestBuilder action(String action, UUID link, UUID warehouse) {
		return "reactivate".equals(action) ? reactivation(link, List.of(warehouse)) : post(LINKS + "/{id}/" + action, link);
	}

	private UUID warehouse(String code, String type, boolean active) {
		return this.jdbc.queryForObject("insert into warehouse (code, name, type, active) values (?, ?, ?, ?) returning id",
				UUID.class, code, "Dépôt " + code, type, active);
	}

	/** Dépôts enregistrés pour la liaison, dans la base de l'entreprise A. */
	private Set<UUID> warehousesOf(UUID link) {
		return Set.copyOf(TenantContext.callAs(this.companyA, () -> this.jdbc.queryForList(
				"select warehouse_id from integration_link_warehouse where link_id = ?", UUID.class, link)));
	}

	private int linkWarehouseRows() {
		return TenantContext.callAs(this.companyA,
				() -> this.jdbc.queryForObject("select count(*) from integration_link_warehouse", Integer.class));
	}

	private MockHttpServletRequestBuilder jsonPost(String path, Map<String, Object> body) {
		return post(path).contentType(MediaType.APPLICATION_JSON).content(this.json.writeValueAsString(body));
	}

	private static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder request, String token) {
		return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
	}

	private static String code(JsonNode approval) {
		return query(URI.create(approval.get("redirectUrl").asString())).get("code");
	}

	/**
	 * Paramètres décodés de l'URL de retour. Lue avec {@link URI}, qui ne journalise rien : l'analyseur d'URL de Spring
	 * trace la requête en TRACE, code compris.
	 */
	private static Map<String, String> query(URI uri) {
		Map<String, String> values = new LinkedHashMap<>();
		for (String pair : uri.getRawQuery().split("&")) {
			int equals = pair.indexOf('=');
			values.put(decode(pair.substring(0, equals)), decode(pair.substring(equals + 1)));
		}
		return values;
	}

	private Link linkOfRequest(String requestUri) {
		UUID id = this.platform.jdbc().sql("""
				select c.link_id from platform.integration_authorization_code c
				join platform.integration_authorization_request r on r.id = c.request_id
				where r.request_uri_hash = :hash""").param("hash", LinkRegistry.sha256Hex(requestUri)).query(UUID.class).single();
		return this.registry.find(id).orElseThrow();
	}

	private String decision(String requestUri) {
		return this.platform.jdbc().sql("select decision from platform.integration_authorization_request where request_uri_hash = :hash")
			.param("hash", LinkRegistry.sha256Hex(requestUri)).query(String.class).optional().orElse(null);
	}

	private int codesOfRequest(String requestUri) {
		return this.platform.jdbc().sql("""
				select count(*) from platform.integration_authorization_code c
				join platform.integration_authorization_request r on r.id = c.request_id
				where r.request_uri_hash = :hash""").param("hash", LinkRegistry.sha256Hex(requestUri)).query(Integer.class).single();
	}

	private int linksOfRequestShop(String shop) {
		return this.platform.jdbc().sql("select count(*) from platform.integration_link where client_id = :c and external_shop_id = :s")
			.param("c", this.client.id()).param("s", shop).query(Integer.class).single();
	}

	private static JsonNode find(JsonNode list, UUID id) {
		for (JsonNode item : list) {
			if (id.toString().equals(item.get("id").asString())) {
				return item;
			}
		}
		return null;
	}

	/** Corps d'erreur sans les champs propres à chaque requête (date, chemin, trace). */
	private String withoutVolatileFields(String body) {
		JsonNode node = this.json.readTree(body);
		return node.get("status").asString() + "|" + node.get("code").asString() + "|" + node.get("message").asString();
	}

	private JsonNode read(ResultActions actions) throws Exception {
		return this.json.readTree(actions.andReturn().getResponse().getContentAsString());
	}

	private String remember(String value) {
		this.sensitive.add(value);
		return value;
	}

	private String verifier() {
		return remember(randomToken() + "-verifier");
	}

	private String basic(String clientId, String secret) {
		return "Basic " + remember(Base64.getEncoder().encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.UTF_8)));
	}

	private static String decode(String value) {
		return value == null ? null : java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
	}

	private static String challenge(String verifier) throws Exception {
		return Base64.getUrlEncoder().withoutPadding()
			.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
	}

	private static String randomToken() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String render(ILoggingEvent event) {
		StringBuilder text = new StringBuilder(String.valueOf(event.getFormattedMessage()));
		if (event.getArgumentArray() != null) {
			for (Object argument : event.getArgumentArray()) {
				text.append(' ').append(argument);
			}
		}
		for (IThrowableProxy error = event.getThrowableProxy(); error != null; error = error.getCause()) {
			text.append(' ').append(error.getMessage());
		}
		return text.toString();
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
		String code = "it-g10-" + UUID.randomUUID().toString().substring(0, 8);
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

	/** Route d'intégration de test : effet immédiat des décisions de gestion sur la chaîne d'intégration (G7). */
	@RestController
	@RequestMapping("/api/integration/v1/test-consent")
	static class ConsentTestController {

		@GetMapping("/catalog")
		@RequiresScope("catalog:read")
		Map<String, Object> catalog() {
			return Map.of("ok", true);
		}

	}

}
