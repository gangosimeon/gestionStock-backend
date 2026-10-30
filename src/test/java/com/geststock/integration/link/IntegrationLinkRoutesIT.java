package com.geststock.integration.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.geststock.platform.PlatformDb;
import com.geststock.support.PostgresTestConfiguration;
import com.geststock.tenancy.TenantContext;
import com.geststock.tenancy.TenantRef;
import com.geststock.tenancy.TenantSchemaRegistry;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Routes de la liaison sur la chaîne d'intégration (lot 2, étape G11 ; contrat {@code GET /link},
 * {@code POST /link/revoke}, schéma {@code Link} ; C2, v14-D10) et vue unique de la liaison, aussi rendue par
 * {@code /oauth/token} (grant {@code authorization_code}). Aucun secret, jeton ni motif libre dans les journaux,
 * y compris en TRACE.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class IntegrationLinkRoutesIT {

	private static final String LINK = "/api/integration/v1/link";

	private static final String REVOKE = "/api/integration/v1/link/revoke";

	private static final String TOKEN = "/api/integration/v1/oauth/token";

	/** Champs du schéma {@code Link} du contrat. */
	private static final Set<String> LINK_FIELDS = Set.of("id", "status", "suspensionReason", "revocationReason",
			"companyCode", "currency", "externalShopId", "scopes", "warehouses", "settings", "createdAt", "lastSyncAt",
			"revokedAt");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private PlatformDb platform;

	/** Espion : contexte d'entreprise observé pendant la révocation. */
	@MockitoSpyBean
	private LinkRegistry registry;

	@Autowired
	private IntegrationTokenService tokens;

	@Autowired
	private TenantSchemaRegistry schemas;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private JsonMapper json;

	private TestLinks links;

	private TenantRef company;

	private String clientSecret;

	private TestLinks.Client client;

	private UUID store;

	private UUID depot;

	private UUID outside;

	private final List<String> sensitive = java.util.Collections.synchronizedList(new ArrayList<>());

	private final Map<Logger, Level> levels = new LinkedHashMap<>();

	private ListAppender<ILoggingEvent> logs;

	@BeforeEach
	void setUp() {
		this.links = new TestLinks(this.platform, this.registry);
		this.company = new TenantRef(this.links.legacyCompany(), "test");
		this.clientSecret = remember("secret-g11-" + UUID.randomUUID());
		this.client = this.links.client(this.clientSecret, null, Set.of("catalog:read", "orders:read", "stock:read"));
		String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
		this.store = warehouse("G11-S-" + suffix, "STORE");
		this.depot = warehouse("G11-D-" + suffix, "WAREHOUSE");
		this.outside = warehouse("G11-X-" + suffix, "STORE");
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
		try {
			List<String> messages = this.logs.list.stream().map(IntegrationLinkRoutesIT::render).toList();
			assertThat(messages).as("aucun secret, jeton ni motif libre dans les journaux")
				.allSatisfy(message -> this.sensitive.forEach(value -> assertThat(message).doesNotContain(value)));
		}
		finally {
			this.sensitive.clear();
			this.platform.jdbc().sql("update platform.company set status = 'ACTIVE' where id = :id")
				.param("id", this.company.companyId()).update();
			this.schemas.upToDate(this.company, 0, 0);
			this.links.cleanUp();
			this.jdbc.update("delete from integration_link_warehouse where warehouse_id in (?, ?, ?)", this.store, this.depot,
					this.outside);
			this.jdbc.update("delete from warehouse where id in (?, ?, ?)", this.store, this.depot, this.outside);
		}
	}

	// --- GET /link ----------------------------------------------------------------------------------------------

	@Test
	void getLinkServesTheContractViewWithEffectiveScopesAndTheLinkWarehousesOnly() throws Exception {
		Linked linked = active("shop-g11-get", Set.of("catalog:read", "orders:read"), this.store, this.depot);
		String token = token(linked, Set.of("catalog:read", "orders:read"));

		JsonNode view = read(this.mvc.perform(bearer(get(LINK), token)).andExpect(status().isOk()));
		assertThat(fieldNames(view)).as("champs du schéma Link").isEqualTo(LINK_FIELDS);
		assertThat(view.get("id").asString()).isEqualTo(linked.id().toString());
		assertThat(view.get("status").asString()).isEqualTo("ACTIVE");
		assertThat(view.get("suspensionReason").isNull()).isTrue();
		assertThat(view.get("revocationReason").isNull()).isTrue();
		assertThat(view.get("companyCode").asString()).isEqualTo("test");
		assertThat(view.get("currency").asString()).isEqualTo("XOF");
		assertThat(view.get("externalShopId").asString()).isEqualTo("shop-g11-get");
		assertThat(strings(view.get("scopes"))).containsExactly("catalog:read", "orders:read");
		assertThat(warehouseIds(view)).containsExactlyInAnyOrder(this.store, this.depot);
		for (JsonNode warehouse : view.get("warehouses")) {
			assertThat(fieldNames(warehouse)).isEqualTo(Set.of("warehouseId", "name", "active"));
			assertThat(warehouse.get("active").asBoolean()).isTrue();
		}
		assertThat(view.get("settings").isObject()).isTrue();
		assertThat(view.get("createdAt").asString()).isNotBlank();
		assertThat(view.get("lastSyncAt").isNull()).isTrue();
		assertThat(view.get("revokedAt").isNull()).isTrue();
		assertThat(view.toString()).doesNotContainIgnoringCase("hash").doesNotContainIgnoringCase("secret");

		// Portées effectives : une portée retirée en base disparaît, avec le même jeton.
		this.platform.jdbc().sql("update platform.integration_link set scopes = ARRAY['catalog:read']::varchar(30)[] where id = :id")
			.param("id", linked.id()).update();
		JsonNode narrowed = read(this.mvc.perform(bearer(get(LINK), token)).andExpect(status().isOk()));
		assertThat(strings(narrowed.get("scopes"))).containsExactly("catalog:read");
	}

	@Test
	void aSuspendedLinkReadsItsStatusAndReasonAndCanRevoke() throws Exception {
		Linked linked = active("shop-g11-suspended", Set.of("catalog:read"), this.store);
		String token = token(linked, Set.of("catalog:read"));
		this.registry.suspend(linked.id(), SuspensionReason.MANUAL);

		this.mvc.perform(bearer(get(LINK), token)).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SUSPENDED"))
			.andExpect(jsonPath("$.suspensionReason").value("MANUAL"));
		revoke(token, null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVOKED"));
	}

	@Test
	void aSuspendedOrMigratingCompanyBlocksTheReadButNotTheRevocation() throws Exception {
		Linked suspendedCompany = active("shop-g11-company", Set.of("catalog:read"), this.store);
		String token = token(suspendedCompany, Set.of("catalog:read"));
		AtomicReference<Optional<TenantRef>> context = new AtomicReference<>();
		doAnswer(invocation -> {
			context.set(TenantContext.current());
			return invocation.callRealMethod();
		}).when(this.registry).revoke(any(UUID.class), any(RevocationReason.class));

		this.platform.jdbc().sql("update platform.company set status = 'SUSPENDED' where id = :id")
			.param("id", this.company.companyId()).update();
		this.mvc.perform(bearer(get(LINK), token)).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("COMPANY_SUSPENDED"));
		revoke(token, null).andExpect(status().isOk());
		assertThat(context.get()).as("aucune entreprise courante pendant la révocation").isEmpty();
		this.platform.jdbc().sql("update platform.company set status = 'ACTIVE' where id = :id")
			.param("id", this.company.companyId()).update();

		Linked migrating = active("shop-g11-migration", Set.of("catalog:read"), this.store);
		String migratingToken = token(migrating, Set.of("catalog:read"));
		this.schemas.upgrading(this.company, 1);
		this.mvc.perform(bearer(get(LINK), migratingToken)).andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("SCHEMA_UPGRADE_IN_PROGRESS"));
		context.set(null);
		revoke(migratingToken, null).andExpect(status().isOk());
		assertThat(context.get()).isEmpty();
	}

	// --- POST /link/revoke --------------------------------------------------------------------------------------

	@Test
	void aRevokedLinkGetsLinkRevokedEverywhereAndTheOthersStayActive() throws Exception {
		Linked linked = active("shop-g11-revoke", Set.of("catalog:read"), this.store);
		Linked other = active("shop-g11-revoke-other", Set.of("catalog:read"), this.store);
		String token = token(linked, Set.of("catalog:read"));
		String reason = remember("Motif-confidentiel-" + UUID.randomUUID());

		JsonNode revoked = read(revoke(token, reason).andExpect(status().isOk()));
		assertThat(fieldNames(revoked)).isEqualTo(LINK_FIELDS);
		assertThat(revoked.get("status").asString()).isEqualTo("REVOKED");
		assertThat(revoked.get("revocationReason").asString()).isEqualTo("SHOP_REQUEST");
		assertThat(revoked.get("revokedAt").asString()).isNotBlank();
		assertThat(revoked.get("warehouses")).as("liaison révoquée : aucun dépôt vendable").isEmpty();
		assertThat(revoked.toString()).doesNotContain(reason);

		this.mvc.perform(bearer(get(LINK), token)).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("LINK_REVOKED"));
		revoke(token, null).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("LINK_REVOKED"));
		this.mvc.perform(credentials(linked)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("invalid_grant")).andExpect(jsonPath("$.code").value("LINK_REVOKED"));

		assertThat(this.registry.find(other.id()).orElseThrow().status()).isEqualTo(LinkStatus.ACTIVE);
	}

	@Test
	void twoSimultaneousRevocationsGiveOneSuccessAndOneLinkRevoked() throws Exception {
		Linked linked = active("shop-g11-race", Set.of("catalog:read"), this.store);
		String token = token(linked, Set.of("catalog:read"));
		CyclicBarrier start = new CyclicBarrier(2);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			List<Future<MockHttpServletResponse>> results = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				MockHttpServletRequestBuilder request = bearer(post(REVOKE), token);
				results.add(pool.submit(() -> {
					start.await(10, TimeUnit.SECONDS);
					return this.mvc.perform(request).andReturn().getResponse();
				}));
			}
			List<Integer> statuses = new ArrayList<>();
			for (Future<MockHttpServletResponse> result : results) {
				MockHttpServletResponse response = result.get(30, TimeUnit.SECONDS);
				statuses.add(response.getStatus());
				if (response.getStatus() == 401) {
					assertThat(this.json.readTree(response.getContentAsString()).get("code").asString()).isEqualTo("LINK_REVOKED");
				}
			}
			assertThat(statuses).containsExactlyInAnyOrder(200, 401);
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void aTooLongReasonIsRefusedAndNeverLogged() throws Exception {
		Linked linked = active("shop-g11-reason", Set.of("catalog:read"), this.store);
		String token = token(linked, Set.of("catalog:read"));
		String tooLong = remember("R" + "x".repeat(500));

		revoke(token, tooLong).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		assertThat(this.registry.find(linked.id()).orElseThrow().status()).isEqualTo(LinkStatus.ACTIVE);

		String accepted = remember("M" + "y".repeat(499));
		revoke(token, accepted).andExpect(status().isOk());
	}

	// --- /oauth/token : vue unique -------------------------------------------------------------------------------

	@Test
	void theCodeExchangeServesTheLinkWarehouses() throws Exception {
		JsonNode body = exchange("shop-g11-exchange", this.store, this.depot);
		assertThat(fieldNames(body.get("link"))).isEqualTo(LINK_FIELDS);
		assertThat(warehouseIds(body.get("link"))).containsExactlyInAnyOrder(this.store, this.depot);
		assertThat(body.get("link_secret").asString()).isNotBlank();
	}

	/**
	 * Base de l'entreprise illisible à l'échange (entreprise suspendue entre l'approbation et l'échange) : l'échange
	 * réussit, le secret est remis, les dépôts sont une liste vide (lus ensuite par {@code GET /link}).
	 */
	@Test
	void anUnreadableCompanyDatabaseNeverLosesTheSecret() throws Exception {
		JsonNode body = exchangeWhile("shop-g11-unreadable", () -> this.platform.jdbc()
			.sql("update platform.company set status = 'SUSPENDED' where id = :id").param("id", this.company.companyId()).update(),
				this.store);
		assertThat(body.get("link_secret").asString()).isNotBlank();
		assertThat(body.get("link").get("status").asString()).isEqualTo("ACTIVE");
		assertThat(body.get("link").get("warehouses")).isEmpty();

		this.platform.jdbc().sql("update platform.company set status = 'ACTIVE' where id = :id")
			.param("id", this.company.companyId()).update();
		JsonNode migrating = exchangeWhile("shop-g11-unreadable-2", () -> this.schemas.upgrading(this.company, 1), this.store);
		assertThat(migrating.get("link_secret").asString()).isNotBlank();
		assertThat(migrating.get("link").get("warehouses")).isEmpty();
	}

	// --- Outils ---------------------------------------------------------------------------------------------

	private record Linked(UUID id, String secret) {
	}

	private Linked active(String shop, Set<String> scopes, UUID... warehouses) {
		Link pending = this.links.pending(this.client, this.company.companyId(), shop, scopes);
		for (UUID warehouse : warehouses) {
			this.jdbc.update("insert into integration_link_warehouse (link_id, warehouse_id, added_by) values (?, ?, ?)",
					pending.id(), warehouse, UUID.randomUUID());
		}
		ActivatedLink activated = this.registry.activate(pending.id());
		return new Linked(pending.id(), remember(activated.secret()));
	}

	private String token(Linked linked, Set<String> scopes) {
		return remember(this.tokens.issue(linked.id(), this.client.clientId(), scopes).value());
	}

	private ResultActions revoke(String token, String reason) throws Exception {
		MockHttpServletRequestBuilder request = bearer(post(REVOKE), token);
		if (reason != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(this.json.writeValueAsString(Map.of("reason", reason)));
		}
		return this.mvc.perform(request);
	}

	private MockHttpServletRequestBuilder credentials(Linked linked) {
		return post(TOKEN).contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.header(HttpHeaders.AUTHORIZATION, basic())
			.param("grant_type", "client_credentials").param("link_id", linked.id().toString())
			.param("link_secret", linked.secret());
	}

	private JsonNode exchange(String shop, UUID... warehouses) throws Exception {
		return exchangeWhile(shop, () -> {
		}, warehouses);
	}

	/** Approbation simulée (demande, liaison en attente avec ses dépôts, code), action, puis échange du code. */
	private JsonNode exchangeWhile(String shop, Runnable beforeExchange, UUID... warehouses) throws Exception {
		String verifier = remember("verificateur-g11-" + UUID.randomUUID() + "-" + UUID.randomUUID());
		String challenge = Base64.getUrlEncoder().withoutPadding()
			.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
		UUID request = this.platform.jdbc().sql("""
				insert into platform.integration_authorization_request (request_uri_hash, client_id, external_shop_id, shop_name,
				  redirect_uri, code_challenge, scopes, state, expires_at, decision, used_at)
				values (:hash, :client, :shop, :name, :redirect, :challenge, ARRAY['catalog:read']::varchar[], repeat('s', 43),
				  now() + interval '10 minutes', 'APPROVED', now())
				returning id""")
			.param("hash", LinkRegistry.sha256Hex(UUID.randomUUID().toString())).param("client", this.client.id())
			.param("shop", shop).param("name", "Boutique " + shop).param("redirect", TestLinks.REDIRECT_URI)
			.param("challenge", challenge).query(UUID.class).single();
		Link pending = this.registry.createPending(request, this.company.companyId(), new Authorizer(UUID.randomUUID(), "admin"),
				Set.of("catalog:read"));
		for (UUID warehouse : warehouses) {
			this.jdbc.update("insert into integration_link_warehouse (link_id, warehouse_id, added_by) values (?, ?, ?)",
					pending.id(), warehouse, UUID.randomUUID());
		}
		String code = remember("code-g11-" + UUID.randomUUID());
		this.platform.jdbc().sql("""
				insert into platform.integration_authorization_code (code_hash, request_id, link_id, expires_at)
				values (:hash, :request, :link, now() + interval '5 minutes')""")
			.param("hash", LinkRegistry.sha256Hex(code)).param("request", request).param("link", pending.id()).update();
		beforeExchange.run();
		JsonNode body = read(this.mvc.perform(post(TOKEN).contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.header(HttpHeaders.AUTHORIZATION, basic())
			.param("grant_type", "authorization_code").param("code", code).param("redirect_uri", TestLinks.REDIRECT_URI)
			.param("code_verifier", verifier))
			.andExpect(status().isOk()));
		remember(body.get("access_token").asString());
		remember(body.get("link_secret").asString());
		return body;
	}

	private String basic() {
		return "Basic " + remember(Base64.getEncoder()
			.encodeToString((this.client.clientId() + ":" + this.clientSecret).getBytes(StandardCharsets.UTF_8)));
	}

	private UUID warehouse(String code, String type) {
		return this.jdbc.queryForObject("insert into warehouse (code, name, type) values (?, ?, ?) returning id", UUID.class, code,
				"Dépôt " + code, type);
	}

	private static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder request, String token) {
		return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
	}

	private JsonNode read(ResultActions actions) throws Exception {
		return this.json.readTree(actions.andReturn().getResponse().getContentAsString());
	}

	private static Set<String> fieldNames(JsonNode node) {
		return new TreeSet<>(node.propertyNames());
	}

	private static List<String> strings(JsonNode array) {
		List<String> values = new ArrayList<>();
		array.forEach(value -> values.add(value.asString()));
		return values;
	}

	private static List<UUID> warehouseIds(JsonNode view) {
		List<UUID> ids = new ArrayList<>();
		view.get("warehouses").forEach(warehouse -> ids.add(UUID.fromString(warehouse.get("warehouseId").asString())));
		return ids;
	}

	private String remember(String value) {
		this.sensitive.add(value);
		return value;
	}

	private static String render(ILoggingEvent event) {
		StringBuilder text = new StringBuilder(String.valueOf(event.getFormattedMessage()));
		if (event.getArgumentArray() != null) {
			for (Object argument : event.getArgumentArray()) {
				text.append(' ').append(argument);
			}
		}
		return text.toString();
	}

}
