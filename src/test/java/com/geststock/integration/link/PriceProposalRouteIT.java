package com.geststock.integration.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.geststock.platform.PlatformDb;
import com.geststock.platform.provisioning.ProvisionerAccess;
import com.geststock.support.PostgresTestConfiguration;
import com.geststock.tenancy.TenantContext;
import com.geststock.tenancy.TenantDataSourceRegistry;
import com.geststock.tenancy.TenantRef;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /prices/proposals} et {@code GET /prices} (lot 3, étape G22a ; D2, C6, décisions P1 à P11) avec deux
 * entreprises réelles : A (l'entreprise de test historique) et B (provisionnée pour ce test, base dédiée).
 *
 * <ul>
 * <li>Proposition idempotente ({@code Idempotency-Key}) : créée ({@code 201}), rejouée à l'identique ({@code 200}),
 * même clé et autre requête ({@code 409 IDEMPOTENCY_KEY_REUSED}).</li>
 * <li>C6 : une seule proposition {@code PROPOSED} par (liaison, produit), y compris sous concurrence ; la précédente
 * passe {@code SUPERSEDED}.</li>
 * <li>Refus sans rien créer : version du prix de référence obsolète ({@code 422 PRICE_VERSION_MISMATCH}), autre
 * devise ({@code 422 PRICE_CURRENCY_MISMATCH}), produit archivé ({@code 422 PRODUCT_ARCHIVED}), produit inexistant ou
 * d'une autre entreprise (même {@code 404 PRODUCT_NOT_FOUND}).</li>
 * <li>Lecture : la liaison ne voit que ses propres propositions ; portées, débit et journaux.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "geststock.platform.provisioning.async=false")
@Import(PostgresTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PriceProposalRouteIT {

	private static final String PROPOSALS = "/api/integration/v1/prices/proposals";

	private static final String PRICES = "/api/integration/v1/prices";

	private static final String SUPER_ADMIN = "it_g22_superadmin";

	private static final String SUPER_ADMIN_PASSWORD = "Plateforme-Test-2026";

	private static final Set<String> PRICING = Set.of("catalog:read", "price:propose");

	/** Loggers que la configuration limite parce qu'ils citent les valeurs reçues (G12, G15, revue G16). */
	private static final Set<String> CAPPED_LOGGERS = Set.of("org.postgresql", "org.springframework.web.method.HandlerMethod",
			"org.springframework.web.servlet.DispatcherServlet");

	/**
	 * Écriture du corps de réponse par Spring MVC, en DEBUG ou TRACE : la réponse renvoie à la boutique son propre prix
	 * proposé. Limite assumée (v17-D2, comme {@code CatalogImageRouteIT}) : ces niveaux ne sont jamais activés en
	 * production. Son contenu n'est pas vérifié ; tous les autres journaux le sont, dont la trace des paramètres SQL.
	 */
	private static final String RESPONSE_WRITER = "org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor";

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

	private final List<UUID> createdCompanies = new ArrayList<>();

	private final List<String> sensitive = java.util.Collections.synchronizedList(new ArrayList<>());

	/** Produits et catégories créés, par entreprise : supprimés après chaque test avec leurs propositions. */
	private final Map<TenantRef, List<UUID[]>> created = new LinkedHashMap<>();

	private final Map<Logger, Level> levels = new LinkedHashMap<>();

	private ListAppender<ILoggingEvent> logs;

	@BeforeAll
	void provisionCompanies() throws Exception {
		this.links = new TestLinks(this.platform, this.registry);
		this.companyA = new TenantRef(this.links.legacyCompany(), "test");
		this.companyB = provisionCompanyB();
	}

	@BeforeEach
	void setUp() {
		LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
		for (Logger logger : context.getLoggerList()) {
			if (logger.getLevel() != null && !CAPPED_LOGGERS.contains(logger.getName())) {
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
			List<String> messages = this.logs.list.stream().filter(event -> !RESPONSE_WRITER.equals(event.getLoggerName()))
				.map(PriceProposalRouteIT::render).toList();
			assertThat(messages).as("aucune valeur reçue dans les journaux")
				.allSatisfy(message -> this.sensitive.forEach(value -> assertThat(message).doesNotContain(value)));
		}
		finally {
			this.sensitive.clear();
			this.created.forEach((company, rows) -> TenantContext.runAs(company, () -> {
				this.jdbc.update("delete from integration_idempotency");
				this.jdbc.update("delete from channel_price_head");
				this.jdbc.update("delete from channel_price");
				for (UUID[] row : rows) {
					this.jdbc.update("delete from product where id = ?", row[0]);
					this.jdbc.update("delete from category where id = ?", row[1]);
				}
			}));
			this.created.clear();
			this.links.cleanUp();
		}
	}

	@AfterAll
	void dropCompanyB() throws Exception {
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

	// --- Création et rejeu (Idempotency-Key) ----------------------------------------------------------------------

	@Test
	void aProposalIsCreatedThenAReplayReturnsTheSameAnswer() throws Exception {
		Linked linked = active(this.companyA, PRICING);
		UUID product = product(this.companyA, "1500.00");
		String key = key();
		String body = proposal(product, "1200", linked.currency(), 0, null);

		String created = propose(linked, key, body).andExpect(status().isCreated())
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
			.andExpect(jsonPath("$.productId").value(product.toString()))
			.andExpect(jsonPath("$.referencePrice").value("1500.00"))
			.andExpect(jsonPath("$.referenceVersion").value(0))
			.andExpect(jsonPath("$.approved").isEmpty())
			.andExpect(jsonPath("$.pending.status").value("PROPOSED"))
			.andExpect(jsonPath("$.pending.price").value("1200.00"))
			.andExpect(jsonPath("$.pending.rejectionReason").isEmpty())
			.andExpect(jsonPath("$.referenceChangedSinceApproval").value(false))
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

		String replayed = propose(linked, key, body).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		assertThat(this.json.readTree(replayed)).as("réponse enregistrée renvoyée telle quelle")
			.isEqualTo(this.json.readTree(created));
		assertThat(statuses(this.companyA, linked, product)).containsExactly("PROPOSED");
	}

	@Test
	void theSameKeyWithAnotherRequestIsAConflictAndCreatesNothing() throws Exception {
		Linked linked = active(this.companyA, PRICING);
		UUID product = product(this.companyA, "1500.00");
		String key = key();
		propose(linked, key, proposal(product, "1200", linked.currency(), 0, null)).andExpect(status().isCreated());

		propose(linked, key, proposal(product, "1300", linked.currency(), 0, null)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
		assertThat(statuses(this.companyA, linked, product)).containsExactly("PROPOSED");
	}

	// --- Concurrence (C6) ----------------------------------------------------------------------------------------

	@Test
	void aNewProposalSupersedesThePendingOne() throws Exception {
		Linked linked = active(this.companyA, PRICING);
		UUID product = product(this.companyA, "1500.00");
		propose(linked, key(), proposal(product, "1200", linked.currency(), 0, null)).andExpect(status().isCreated());
		propose(linked, key(), proposal(product, "1250.50", linked.currency(), 0, null)).andExpect(status().isCreated())
			.andExpect(jsonPath("$.pending.price").value("1250.50"));

		assertThat(statuses(this.companyA, linked, product)).containsExactly("SUPERSEDED", "PROPOSED");
	}

	@Test
	void concurrentProposalsLeaveExactlyOnePendingTheLastToTakeTheLock() throws Exception {
		Linked linked = active(this.companyA, PRICING);
		UUID product = product(this.companyA, "1500.00");
		int calls = 10;
		CyclicBarrier start = new CyclicBarrier(calls);
		ExecutorService pool = Executors.newFixedThreadPool(calls);
		try {
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 1; i <= calls; i++) {
				String body = proposal(product, Integer.toString(1000 + i), linked.currency(), 0, null);
				String key = key();
				results.add(pool.submit(() -> {
					start.await(10, TimeUnit.SECONDS);
					return propose(linked, key, body).andReturn().getResponse().getStatus();
				}));
			}
			for (Future<Integer> result : results) {
				assertThat(result.get(60, TimeUnit.SECONDS)).isEqualTo(201);
			}
		}
		finally {
			pool.shutdownNow();
		}
		List<String> statuses = statuses(this.companyA, linked, product);
		assertThat(statuses).hasSize(calls);
		assertThat(statuses.stream().filter("PROPOSED"::equals)).as("une seule proposition en attente").hasSize(1);
		assertThat(statuses.get(calls - 1)).as("la dernière à prendre le verrou").isEqualTo("PROPOSED");
		assertThat(TenantContext.callAs(this.companyA, () -> this.jdbc.queryForList(
				"select price_version from channel_price where link_id = ? order by price_version", Long.class, linked.id())))
			.as("rangs attribués sous le verrou, sans trou ni doublon")
			.containsExactlyElementsOf(IntStream.rangeClosed(1, calls).mapToObj(Long::valueOf).toList());
	}

	// --- Refus sans rien créer ----------------------------------------------------------------------------------

	@Test
	void anObsoleteReferenceVersionIsRefusedAndNothingIsCreated() throws Exception {
		Linked linked = active(this.companyA, PRICING);
		UUID product = product(this.companyA, "1500.00");
		TenantContext.runAs(this.companyA, () -> this.jdbc.update("update product set version = version + 1 where id = ?", product));

		propose(linked, key(), proposal(product, "1200", linked.currency(), 0, null)).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("PRICE_VERSION_MISMATCH"));
		assertThat(statuses(this.companyA, linked, product)).isEmpty();
		assertThat(idempotencyRows(this.companyA, linked)).isZero();

		propose(linked, key(), proposal(product, "1200", linked.currency(), 1, null)).andExpect(status().isCreated())
			.andExpect(jsonPath("$.referenceVersion").value(1));
	}

	@Test
	void anotherCurrencyIsRefused() throws Exception {
		Linked linked = active(this.companyA, PRICING);
		UUID product = product(this.companyA, "1500.00");
		String other = "EUR".equals(linked.currency()) ? "USD" : "EUR";

		propose(linked, key(), proposal(product, "1200", other, 0, null)).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("PRICE_CURRENCY_MISMATCH"));
		assertThat(statuses(this.companyA, linked, product)).isEmpty();
	}

	@Test
	void aProductOfAnotherCompanyOrUnknownIsNotFoundAndAnArchivedProductIsRefused() throws Exception {
		Linked linked = active(this.companyA, PRICING);
		UUID ofB = product(this.companyB, "900.00");

		String other = propose(linked, key(), proposal(ofB, "800", linked.currency(), 0, null)).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"))
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		String unknown = propose(linked, key(), proposal(UUID.randomUUID(), "800", linked.currency(), 0, null))
			.andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		assertThat(this.json.readTree(other).get("message")).as("même réponse").isEqualTo(this.json.readTree(unknown).get("message"));

		UUID archived = product(this.companyA, "1500.00");
		TenantContext.runAs(this.companyA, () -> this.jdbc.update("update product set active = false where id = ?", archived));
		propose(linked, key(), proposal(archived, "1200", linked.currency(), 0, null))
			.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("PRODUCT_ARCHIVED"));
		assertThat(statuses(this.companyA, linked, archived)).isEmpty();
	}

	// --- Requête stricte -------------------------------------------------------------------------------------------

	@Test
	void theIdempotencyKeyIsRequiredAndBounded() throws Exception {
		Linked linked = active(this.companyA, PRICING);
		UUID product = product(this.companyA, "1500.00");
		String body = proposal(product, "1200", linked.currency(), 0, null);

		this.mvc.perform(post(PROPOSALS).header(HttpHeaders.AUTHORIZATION, "Bearer " + linked.token())
			.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
		for (String invalid : List.of("court", "x".repeat(101), "avec espace", "accent-é-clé")) {
			propose(linked, invalid, body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
		}
		assertThat(statuses(this.companyA, linked, product)).isEmpty();
	}

	@Test
	void anInvalidBodyIsRefusedNamingTheFieldOrAsMalformed() throws Exception {
		Linked linked = active(this.companyA, PRICING);
		UUID product = product(this.companyA, "1500.00");
		String currency = linked.currency();

		for (String price : List.of("0", "-1", "1.234", "abc", "", "1e3", "12345678901234")) {
			propose(linked, key(), proposal(product, price, currency, 0, null)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED")).andExpect(jsonPath("$.errors[0].field").value("price"));
		}
		propose(linked, key(), "{\"price\": \"10\", \"currency\": \"" + currency + "\", \"basedOnReferenceVersion\": 0}")
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("productId"));
		propose(linked, key(), proposal(product, "10", currency, 0, "n".repeat(501))).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("note"));
		for (String body : List.of("{\"productId\": \"" + product + "\", \"price\": 10, \"currency\": \"" + currency
				+ "\", \"basedOnReferenceVersion\": 0}", "{\"productId\": \"" + product + "\", \"price\": \"10\", \"currency\": \""
						+ currency + "\", \"basedOnReferenceVersion\": \"0\"}",
				"{\"productId\": \"" + product + "\", \"productId\": \"" + product + "\"}", "pas du json", "[1]")) {
			propose(linked, key(), body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
		}
		this.mvc.perform(post(PROPOSALS).header(HttpHeaders.AUTHORIZATION, "Bearer " + linked.token()).header("Idempotency-Key", key())
			.contentType(MediaType.TEXT_PLAIN).content(proposal(product, "10", currency, 0, null)))
			.andExpect(status().isUnsupportedMediaType());
		assertThat(statuses(this.companyA, linked, product)).isEmpty();
	}

	// --- Lecture et isolation ----------------------------------------------------------------------------------

	@Test
	void eachLinkReadsOnlyItsOwnProposals() throws Exception {
		Linked first = active(this.companyA, PRICING);
		Linked second = active(this.companyA, PRICING);
		UUID product = product(this.companyA, "1500.00");
		UUID untouched = product(this.companyA, "700.00");
		propose(first, key(), proposal(product, "1200", first.currency(), 0, null)).andExpect(status().isCreated());

		JsonNode mine = prices(first, product + "," + untouched + "," + UUID.randomUUID());
		assertThat(mine.get("items")).as("produit inconnu omis").hasSize(2);
		JsonNode priced = item(mine, product);
		assertThat(priced.get("pending").get("status").asString()).isEqualTo("PROPOSED");
		assertThat(priced.get("approved").isNull()).isTrue();
		assertThat(item(mine, untouched).get("pending").isNull()).isTrue();
		assertThat(item(mine, untouched).get("referencePrice").asString()).isEqualTo("700.00");

		JsonNode theirs = prices(second, product.toString());
		assertThat(item(theirs, product).get("pending").isNull()).as("proposition d'une autre liaison invisible").isTrue();

		Linked ofB = active(this.companyB, PRICING);
		assertThat(prices(ofB, product.toString()).get("items")).as("produit d'une autre entreprise omis").isEmpty();
	}

	@Test
	void theProductIdsParameterIsRequiredCanonicalAndBounded() throws Exception {
		Linked linked = active(this.companyA, PRICING);
		read(linked, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
		read(linked, "").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
		read(linked, "1-1-1-1-1").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
		String many = IntStream.range(0, 101).mapToObj(i -> UUID.randomUUID().toString()).collect(Collectors.joining(","));
		read(linked, many).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("productIds"));
		this.mvc.perform(get(PRICES).header(HttpHeaders.AUTHORIZATION, "Bearer " + linked.token())
			.param("productIds", UUID.randomUUID().toString()).param("productIds", UUID.randomUUID().toString()))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
	}

	// --- Portées, débit et journaux ------------------------------------------------------------------------------

	@Test
	void eachRouteRequiresItsScope() throws Exception {
		Linked readOnly = active(this.companyA, Set.of("catalog:read"));
		Linked proposeOnly = active(this.companyA, Set.of("price:propose"));
		UUID product = product(this.companyA, "1500.00");

		propose(readOnly, key(), proposal(product, "1200", readOnly.currency(), 0, null)).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("INSUFFICIENT_SCOPE"));
		read(proposeOnly, product.toString()).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("INSUFFICIENT_SCOPE"));
		this.mvc.perform(post(PROPOSALS).header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
			.content(proposal(product, "1200", "XOF", 0, null))).andExpect(status().isUnauthorized());
		assertThat(statuses(this.companyA, readOnly, product)).isEmpty();
	}

	@Test
	void theSixtyFirstProposalOfTheLinkIsRefusedBeforeReadingTheBodyAndAnotherLinkIsNotAffected() throws Exception {
		Linked linked = active(this.companyA, PRICING);
		Linked other = active(this.companyA, PRICING);
		UUID product = product(this.companyA, "1500.00");
		awaitFreshWindow();

		for (int i = 0; i < 30; i++) {
			propose(linked, key(), "illisible").andExpect(status().isBadRequest());
		}
		for (int i = 0; i < 30; i++) {
			propose(linked, key(), proposal(product, Integer.toString(1000 + i), linked.currency(), 0, null))
				.andExpect(status().isCreated());
		}
		propose(linked, key(), proposal(product, "999", linked.currency(), 0, null)).andExpect(status().isTooManyRequests())
			.andExpect(header().exists(HttpHeaders.RETRY_AFTER)).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
		assertThat(statuses(this.companyA, linked, product)).as("l'appel refusé ne crée rien").hasSize(30);

		propose(other, key(), proposal(product, "999", other.currency(), 0, null)).andExpect(status().isCreated());
	}

	@Test
	void noReceivedValueReachesTheLogs() throws Exception {
		Linked linked = active(this.companyA, PRICING);
		UUID product = product(this.companyA, "1500.00");
		String note = remember("g22notemarqueur" + UUID.randomUUID().toString().substring(0, 8));
		String price = "4321.97";
		remember(price);
		String key = key();

		propose(linked, key, proposal(product, price, linked.currency(), 0, note)).andExpect(status().isCreated());
		propose(linked, key, proposal(product, price, linked.currency(), 0, note)).andExpect(status().isOk());
		propose(linked, key, proposal(product, "4321.98", linked.currency(), 0, note)).andExpect(status().isConflict());
		String marker = remember("g22marqueur" + UUID.randomUUID().toString().substring(0, 8));
		propose(linked, key(), proposal(product, marker, linked.currency(), 0, null)).andExpect(status().isBadRequest());
		propose(linked, key(), "{\"" + marker + "\": 1}").andExpect(status().isBadRequest());
		propose(linked, marker + " invalide", proposal(product, "10", linked.currency(), 0, null)).andExpect(status().isBadRequest());
		read(linked, marker).andExpect(status().isBadRequest());
	}

	// --- Outils ------------------------------------------------------------------------------------------------

	private record Linked(UUID id, String token, String currency) {
	}

	private Linked active(TenantRef company, Set<String> scopes) {
		Link link = this.links.active(this.links.client(), company.companyId(), "shop-g22-" + UUID.randomUUID(), scopes);
		String clientId = this.platform.jdbc().sql("select client_id from platform.integration_client where id = :id")
			.param("id", link.clientId()).query(String.class).single();
		return new Linked(link.id(), remember(this.tokens.issue(link.id(), clientId, scopes).value()), link.currency());
	}

	/** Produit actif de l'entreprise, au prix de référence donné, version 0. */
	private UUID product(TenantRef company, String retailPrice) {
		return TenantContext.callAs(company, () -> {
			UUID category = this.jdbc.queryForObject("insert into category (name) values (?) returning id", UUID.class,
					"Cat G22 " + UUID.randomUUID().toString().substring(0, 8));
			UUID product = this.jdbc.queryForObject("""
					insert into product (sku, name, category_id, retail_price) values (?, ?, ?, ?::numeric) returning id""",
					UUID.class, "G22-" + UUID.randomUUID().toString().substring(0, 12), "Produit G22", category, retailPrice);
			this.created.computeIfAbsent(company, ignored -> new ArrayList<>()).add(new UUID[] { product, category });
			return product;
		});
	}

	private static String proposal(UUID product, String price, String currency, long basedOn, String note) {
		return "{\"productId\": \"" + product + "\", \"price\": \"" + price + "\", \"currency\": \"" + currency
				+ "\", \"basedOnReferenceVersion\": " + basedOn + (note != null ? ", \"note\": \"" + note + "\"" : "") + "}";
	}

	private String key() {
		return remember("g22-cle-" + UUID.randomUUID());
	}

	private ResultActions propose(Linked linked, String key, String body) throws Exception {
		return this.mvc.perform(post(PROPOSALS).header(HttpHeaders.AUTHORIZATION, "Bearer " + linked.token())
			.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions read(Linked linked, String productIds) throws Exception {
		var request = get(PRICES).header(HttpHeaders.AUTHORIZATION, "Bearer " + linked.token());
		return this.mvc.perform(productIds != null ? request.param("productIds", productIds) : request);
	}

	private JsonNode prices(Linked linked, String productIds) throws Exception {
		return this.json.readTree(read(linked, productIds).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
	}

	private static JsonNode item(JsonNode page, UUID product) {
		for (JsonNode item : page.get("items")) {
			if (item.get("productId").asString().equals(product.toString())) {
				return item;
			}
		}
		throw new AssertionError("produit absent : " + product);
	}

	/** Statuts des propositions de la liaison pour le produit, par rang croissant. */
	private List<String> statuses(TenantRef company, Linked linked, UUID product) {
		return TenantContext.callAs(company, () -> this.jdbc.queryForList(
				"select status from channel_price where link_id = ? and product_id = ? order by price_version", String.class,
				linked.id(), product));
	}

	private int idempotencyRows(TenantRef company, Linked linked) {
		return TenantContext.callAs(company, () -> this.jdbc.queryForObject(
				"select count(*) from integration_idempotency where link_id = ?", Integer.class, linked.id()));
	}

	private String remember(String value) {
		this.sensitive.add(value);
		return value;
	}

	private static String render(ILoggingEvent event) {
		StringBuilder text = new StringBuilder(event.getFormattedMessage());
		for (var proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
			text.append('\n').append(proxy.getClassName()).append(": ").append(proxy.getMessage());
		}
		return text.toString();
	}

	/** Fenêtre fixe d'une minute : attendre la suivante s'il reste moins de 30 secondes (horloge de PostgreSQL). */
	private void awaitFreshWindow() {
		double remaining = this.platform.jdbc().sql("select 60 - mod(extract(epoch from clock_timestamp()), 60)::float8")
			.query(Double.class).single();
		if (remaining < 30) {
			try {
				Thread.sleep((long) (remaining * 1000) + 200);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
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
		String code = "it-g22-" + UUID.randomUUID().toString().substring(0, 8);
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

}
