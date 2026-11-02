package com.geststock.integration.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import com.geststock.platform.PlatformDb;
import com.geststock.platform.provisioning.ProvisionerAccess;
import com.geststock.stock.StockFixtures;
import com.geststock.stock.StockMovementCommand;
import com.geststock.stock.StockMovementType;
import com.geststock.stock.StockService;
import com.geststock.stock.StockSourceType;
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
 * {@code GET /catalog/changes} (lot 3, étape G16 ; contrat {@code listCatalogChanges}, plan § 29) avec deux entreprises
 * réelles : A (l'entreprise de test historique) et B (provisionnée pour ce test, base dédiée). Le flux lu est celui de
 * l'entreprise de la liaison, sans filtre (Q2 : catalogue actif entier), relu en base à chaque appel (P0-5) ; portée
 * {@code catalog:read} ; 120 appels par minute et par liaison (v13-D4). Codes du flux : {@code INVALID_PARAMETER},
 * {@code VALIDATION_FAILED}, {@code CURSOR_AHEAD}, {@code CURSOR_EXPIRED}. Aucune valeur reçue dans les journaux,
 * y compris en TRACE (G15).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "geststock.platform.provisioning.async=false")
@Import(PostgresTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogChangesRouteIT {

	private static final String CHANGES = "/api/integration/v1/catalog/changes";

	private static final String FEED_STATE_ID = "00000000-0000-0000-0000-000000000001";

	private static final String SUPER_ADMIN = "it_g16_superadmin";

	private static final String SUPER_ADMIN_PASSWORD = "Plateforme-Test-2026";

	private static final Set<String> CATALOG = Set.of("catalog:read");

	/** Champs du schéma {@code ChangeEventBase} du contrat. */
	private static final Set<String> EVENT_FIELDS = Set.of("seq", "type", "objectId", "version", "operation", "occurredAt",
			"data");

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

	@Autowired
	private StockService stock;

	@Autowired
	private TransactionTemplate transactions;

	private StockFixtures fixtures;

	/** Auteur du dernier stock reçu (dépôts de la liaison). */
	private UUID actor;

	private TestLinks links;

	private TenantRef companyA;

	private TenantRef companyB;

	private final List<UUID> createdCompanies = new ArrayList<>();

	private final List<String> sensitive = new ArrayList<>();

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
		this.fixtures = new StockFixtures(this.jdbc, this.transactions);
		resetFeed(this.companyA);
		resetFeed(this.companyB);
		LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
		for (Logger logger : context.getLoggerList()) {
			// Sauf le pilote PostgreSQL, que la configuration fixe à INFO (sa trace protocolaire afficherait chaque valeur
			// liée) : tous les autres journaux en TRACE, ceux de Spring MVC compris.
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
			List<String> messages = this.logs.list.stream().map(CatalogChangesRouteIT::render).toList();
			assertThat(messages).as("aucune valeur reçue dans les journaux")
				.allSatisfy(message -> this.sensitive.forEach(value -> assertThat(message).doesNotContain(value)));
		}
		finally {
			this.sensitive.clear();
			this.links.cleanUp();
			// Dépôts de liaison supprimés avec leurs dépôts (clé étrangère en cascade).
			this.fixtures.purge();
			resetFeed(this.companyA);
			resetFeed(this.companyB);
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

	// --- Page servie -------------------------------------------------------------------------------------------

	@Test
	void theLinkReadsThePageOfItsCompanyInTheContractShape() throws Exception {
		UUID category = insert(this.companyA, "CATEGORY", "UPSERT", 3, "{\"name\":\"Boissons\"}");
		UUID archived = insert(this.companyA, "CATEGORY", "ARCHIVE", 4, null);
		String token = activeToken(this.companyA, CATALOG);

		ResultActions first = this.mvc.perform(bearer(get(CHANGES).param("after", "0"), token))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
		JsonNode page = read(first);
		assertThat(fieldNames(page)).isEqualTo(Set.of("items", "hasMore", "lastSeq"));
		assertThat(page.get("lastSeq").asLong()).isEqualTo(2L);
		assertThat(page.get("hasMore").asBoolean()).isFalse();
		assertThat(page.get("items")).hasSize(2);

		JsonNode upsert = page.get("items").get(0);
		assertThat(fieldNames(upsert)).isEqualTo(EVENT_FIELDS);
		assertThat(upsert.get("seq").asLong()).isEqualTo(1L);
		assertThat(upsert.get("type").asString()).isEqualTo("CATEGORY");
		assertThat(upsert.get("objectId").asString()).isEqualTo(category.toString());
		assertThat(upsert.get("version").asLong()).isEqualTo(3L);
		assertThat(upsert.get("operation").asString()).isEqualTo("UPSERT");
		assertThat(upsert.get("occurredAt").asString()).isNotBlank();
		assertThat(upsert.get("data").get("name").asString()).isEqualTo("Boissons");

		JsonNode archive = page.get("items").get(1);
		assertThat(archive.get("objectId").asString()).isEqualTo(archived.toString());
		assertThat(archive.get("operation").asString()).isEqualTo("ARCHIVE");
		assertThat(archive.has("data")).as("data facultatif pour ARCHIVE, absent s'il n'y en a pas").isFalse();

		JsonNode nothingNew = read(this.mvc.perform(bearer(get(CHANGES).param("after", "2"), token)).andExpect(status().isOk()));
		assertThat(nothingNew.get("items")).isEmpty();
		assertThat(nothingNew.get("lastSeq").asLong()).as("after si rien n'a été examiné").isEqualTo(2L);
		assertThat(nothingNew.get("hasMore").asBoolean()).isFalse();
	}

	@Test
	void eachCallReadsTheDatabaseAgainWithoutCache() throws Exception {
		String token = activeToken(this.companyA, CATALOG);
		JsonNode empty = read(this.mvc.perform(bearer(get(CHANGES).param("after", "0"), token)).andExpect(status().isOk()));
		assertThat(empty.get("items")).isEmpty();

		UUID category = insert(this.companyA, "CATEGORY", "UPSERT", 1, "{}");

		JsonNode page = read(this.mvc.perform(bearer(get(CHANGES).param("after", "0"), token)).andExpect(status().isOk()));
		assertThat(page.get("items")).hasSize(1);
		assertThat(page.get("items").get(0).get("objectId").asString()).isEqualTo(category.toString());
	}

	@Test
	void theLimitBoundsTheEventsExaminedAndHasMoreAsksForTheNextPage() throws Exception {
		for (int i = 0; i < 3; i++) {
			insert(this.companyA, "CATEGORY", "UPSERT", 1, "{}");
		}
		String token = activeToken(this.companyA, CATALOG);

		JsonNode first = read(this.mvc.perform(bearer(get(CHANGES).param("after", "0").param("limit", "2"), token))
			.andExpect(status().isOk()));
		assertThat(first.get("items")).hasSize(2);
		assertThat(first.get("lastSeq").asLong()).isEqualTo(2L);
		assertThat(first.get("hasMore").asBoolean()).isTrue();

		JsonNode second = read(this.mvc.perform(bearer(get(CHANGES).param("after", "2").param("limit", "500"), token))
			.andExpect(status().isOk()));
		assertThat(second.get("items")).hasSize(1);
		assertThat(second.get("lastSeq").asLong()).isEqualTo(3L);
		assertThat(second.get("hasMore").asBoolean()).isFalse();
	}

	@Test
	void anEventTypeUnknownToThisVersionIsServedAsStored() throws Exception {
		insert(this.companyA, "FUTURE_TYPE", "UPSERT", 7, "{\"any\":1}");
		String token = activeToken(this.companyA, CATALOG);

		JsonNode page = read(this.mvc.perform(bearer(get(CHANGES).param("after", "0"), token)).andExpect(status().isOk()));
		assertThat(page.get("items")).hasSize(1);
		assertThat(page.get("items").get(0).get("type").asString()).isEqualTo("FUTURE_TYPE");
		assertThat(page.get("items").get(0).get("version").asLong()).isEqualTo(7L);
	}

	// --- Disponibilité réservée à stock:read ---------------------------------------------------------------------

	@Test
	void withCatalogReadOnlyNoStockQuantityIsServedAndOtherEventsStillAre() throws Exception {
		Stocked stocked = stockedProduct(7);
		Link link = this.links.active(this.links.client(), this.companyA.companyId(), "shop-g16-" + UUID.randomUUID(), CATALOG);
		addWarehouse(link, stocked.warehouse());
		List<UUID> served = mixedFeed(stocked.product());

		String body = this.mvc.perform(bearer(get(CHANGES).param("after", "0"), token(link, CATALOG)))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		JsonNode page = this.json.readTree(body);
		assertThat(types(page)).containsExactly("CATEGORY", "PRODUCT", "PRODUCT_IMAGE", "FUTURE_TYPE");
		assertThat(objectIds(page)).containsExactlyElementsOf(served);
		assertThat(page.get("lastSeq").asLong()).as("événements écartés examinés (P1)").isEqualTo(6L);
		assertThat(page.get("hasMore").asBoolean()).isFalse();
		assertThat(body).as("aucune quantité de stock").doesNotContain("AVAILABILITY", "available", "perWarehouse",
				stocked.warehouse().toString());
	}

	@Test
	void withCatalogReadOnlyThePaginationAndTheCursorAdvanceOverFilteredEvents() throws Exception {
		Stocked stocked = stockedProduct(7);
		Link link = this.links.active(this.links.client(), this.companyA.companyId(), "shop-g16-" + UUID.randomUUID(), CATALOG);
		addWarehouse(link, stocked.warehouse());
		mixedFeed(stocked.product());
		String token = token(link, CATALOG);

		JsonNode first = page(token, 0, 2);
		assertThat(types(first)).containsExactly("CATEGORY");
		assertThat(first.get("lastSeq").asLong()).isEqualTo(2L);
		assertThat(first.get("hasMore").asBoolean()).isTrue();

		JsonNode onlyFiltered = page(token, 3, 1);
		assertThat(onlyFiltered.get("items")).as("page vide : seul un événement écarté examiné").isEmpty();
		assertThat(onlyFiltered.get("lastSeq").asLong()).as("le curseur avance quand même").isEqualTo(4L);
		assertThat(onlyFiltered.get("hasMore").asBoolean()).isTrue();

		JsonNode second = page(token, 2, 2);
		assertThat(types(second)).containsExactly("PRODUCT");
		assertThat(second.get("lastSeq").asLong()).isEqualTo(4L);

		JsonNode last = page(token, 4, 2);
		assertThat(types(last)).containsExactly("PRODUCT_IMAGE", "FUTURE_TYPE");
		assertThat(last.get("lastSeq").asLong()).isEqualTo(6L);
		assertThat(last.get("hasMore").asBoolean()).isFalse();
	}

	@Test
	void withCatalogReadAndStockReadTheAvailabilityOfTheLinkWarehousesIsServed() throws Exception {
		Stocked stocked = stockedProduct(7);
		Set<String> scopes = Set.of("catalog:read", "stock:read");
		Link link = this.links.active(this.links.client(), this.companyA.companyId(), "shop-g16-" + UUID.randomUUID(), scopes);
		addWarehouse(link, stocked.warehouse());
		mixedFeed(stocked.product());

		JsonNode page = page(token(link, scopes), 0, 100);
		assertThat(types(page)).containsExactly("CATEGORY", "PRODUCT", "AVAILABILITY", "PRODUCT_IMAGE", "FUTURE_TYPE");
		assertThat(page.get("lastSeq").asLong()).isEqualTo(6L);
		JsonNode availability = page.get("items").get(2);
		assertThat(availability.get("objectId").asString()).isEqualTo(stocked.product().toString());
		assertThat(availability.get("seq").asLong()).as("événements minces fusionnés : le plus grand seq").isEqualTo(4L);
		assertThat(availability.get("version").asLong()).isEqualTo(4L);
		assertThat(availability.get("data").get("available").asInt()).isEqualTo(7);
		assertThat(availability.get("data").get("perWarehouse").get(0).get("warehouseId").asString())
			.isEqualTo(stocked.warehouse().toString());
	}

	// --- Isolation des entreprises -------------------------------------------------------------------------------

	@Test
	void aLinkOfAReadsOnlyTheFeedOfAAndACursorOfBIsAheadForA() throws Exception {
		UUID ofA = insert(this.companyA, "CATEGORY", "UPSERT", 1, "{}");
		List<UUID> ofB = List.of(insert(this.companyB, "CATEGORY", "UPSERT", 1, "{}"),
				insert(this.companyB, "CATEGORY", "UPSERT", 1, "{}"), insert(this.companyB, "CATEGORY", "UPSERT", 1, "{}"));
		String tokenA = activeToken(this.companyA, CATALOG);
		String tokenB = activeToken(this.companyB, CATALOG);

		JsonNode pageA = read(this.mvc.perform(bearer(get(CHANGES).param("after", "0"), tokenA)).andExpect(status().isOk()));
		assertThat(objectIds(pageA)).containsExactly(ofA);
		assertThat(pageA.get("lastSeq").asLong()).isEqualTo(1L);

		JsonNode pageB = read(this.mvc.perform(bearer(get(CHANGES).param("after", "0"), tokenB)).andExpect(status().isOk()));
		assertThat(objectIds(pageB)).containsExactlyElementsOf(ofB);

		// Curseur de B (3) présenté par la liaison de A, dont le flux s'arrête à 1 : jamais une page vide silencieuse.
		this.mvc.perform(bearer(get(CHANGES).param("after", "3"), tokenA))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CURSOR_AHEAD"));
		assertThat(TenantContext.current()).as("contexte vidé après la requête").isEmpty();
	}

	// --- Codes du flux -------------------------------------------------------------------------------------------

	@Test
	void aMissingNonNumericOrRepeatedParameterIsAnInvalidParameter() throws Exception {
		String token = activeToken(this.companyA, CATALOG);

		invalidParameter(get(CHANGES), token, "Paramètre requis : after.");
		invalidParameter(get(CHANGES).param("after", ""), token, "Paramètre invalide : after.");
		invalidParameter(get(CHANGES).param("after", "abc"), token, "Paramètre invalide : after.");
		invalidParameter(get(CHANGES).param("after", "1.5"), token, "Paramètre invalide : after.");
		invalidParameter(get(CHANGES).param("after", " 1"), token, "Paramètre invalide : after.");
		invalidParameter(get(CHANGES).param("after", "99999999999999999999"), token, "Paramètre invalide : after.");
		invalidParameter(get(CHANGES).param("after", "0").param("limit", "abc"), token, "Paramètre invalide : limit.");
		invalidParameter(get(CHANGES).param("after", "0").param("limit", ""), token, "Paramètre invalide : limit.");
		invalidParameter(get(CHANGES).param("after", "0", "1"), token, "Paramètre répété : after.");
		invalidParameter(get(CHANGES).param("after", "0").param("limit", "1", "2"), token, "Paramètre répété : limit.");
	}

	@Test
	void aValueOutOfBoundsIsAValidationFailureNamingTheParameter() throws Exception {
		String token = activeToken(this.companyA, CATALOG);

		outOfBounds(get(CHANGES).param("after", "-1"), token, "after");
		outOfBounds(get(CHANGES).param("after", "0").param("limit", "0"), token, "limit");
		outOfBounds(get(CHANGES).param("after", "0").param("limit", "501"), token, "limit");
		this.mvc.perform(bearer(get(CHANGES).param("after", "0").param("limit", "1"), token)).andExpect(status().isOk());
		this.mvc.perform(bearer(get(CHANGES).param("after", "0").param("limit", "500"), token)).andExpect(status().isOk());
	}

	@Test
	void aCursorAheadOfTheFeedIsAConflict() throws Exception {
		insert(this.companyA, "CATEGORY", "UPSERT", 1, "{}");
		String token = activeToken(this.companyA, CATALOG);
		this.mvc.perform(bearer(get(CHANGES).param("after", "0"), token)).andExpect(status().isOk());

		this.mvc.perform(bearer(get(CHANGES).param("after", "2"), token))
			.andExpect(status().isConflict())
			.andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE))
			.andExpect(jsonPath("$.code").value("CURSOR_AHEAD"));
	}

	@Test
	void aCursorBelowThePurgeMarkHasExpiredAndTheMarkItselfIsAccepted() throws Exception {
		for (int i = 0; i < 3; i++) {
			insert(this.companyA, "CATEGORY", "UPSERT", 1, "{}");
		}
		String token = activeToken(this.companyA, CATALOG);
		this.mvc.perform(bearer(get(CHANGES).param("after", "0"), token)).andExpect(status().isOk());
		TenantContext.runAs(this.companyA, () -> {
			this.jdbc.update("delete from integration_outbox where seq <= 2");
			this.jdbc.update("update integration_feed_state set purged_through_seq = 2");
		});

		this.mvc.perform(bearer(get(CHANGES).param("after", "1"), token))
			.andExpect(status().isGone())
			.andExpect(jsonPath("$.code").value("CURSOR_EXPIRED"));
		JsonNode atMark = read(this.mvc.perform(bearer(get(CHANGES).param("after", "2"), token)).andExpect(status().isOk()));
		assertThat(atMark.get("items")).hasSize(1);
		assertThat(atMark.get("lastSeq").asLong()).isEqualTo(3L);
	}

	// --- Autorisation et débit -----------------------------------------------------------------------------------

	@Test
	void withoutCatalogReadTheRouteIsForbiddenAndASuspendedLinkIsRefused() throws Exception {
		this.mvc.perform(bearer(get(CHANGES).param("after", "0"), activeToken(this.companyA, Set.of("orders:read"))))
			.andExpect(status().isForbidden())
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\""))
			.andExpect(jsonPath("$.code").value("INSUFFICIENT_SCOPE"));

		Link link = this.links.active(this.links.client(), this.companyA.companyId(), "shop-g16-" + UUID.randomUUID(), CATALOG);
		String token = token(link, CATALOG);
		this.registry.suspend(link.id(), SuspensionReason.MANUAL);
		this.mvc.perform(bearer(get(CHANGES).param("after", "0"), token))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("LINK_SUSPENDED"));

		this.mvc.perform(get(CHANGES).param("after", "0"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
		// Méthode non servie : aucune règle de route, refus de la chaîne.
		this.mvc.perform(post(CHANGES).param("after", "0").header(HttpHeaders.AUTHORIZATION, "Bearer " + activeToken(this.companyA, CATALOG)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.items").doesNotExist());
	}

	@Test
	void theHundredTwentyFirstCallOfTheLinkInTheMinuteIsRefusedAndAnotherLinkIsNotAffected() throws Exception {
		String token = activeToken(this.companyA, CATALOG);
		String other = activeToken(this.companyA, CATALOG);
		awaitFreshWindow();

		for (int i = 0; i < 120; i++) {
			this.mvc.perform(bearer(get(CHANGES).param("after", "0"), token)).andExpect(status().isOk());
		}
		this.mvc.perform(bearer(get(CHANGES).param("after", "0"), token))
			.andExpect(status().isTooManyRequests())
			.andExpect(header().exists(HttpHeaders.RETRY_AFTER))
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
			.andExpect(jsonPath("$.code").value("RATE_LIMITED"));
		// Paramètres invalides : le quota est déjà épuisé, la limite passe avant leur lecture.
		this.mvc.perform(bearer(get(CHANGES).param("after", "x"), token)).andExpect(status().isTooManyRequests());

		this.mvc.perform(bearer(get(CHANGES).param("after", "0"), other)).andExpect(status().isOk());
	}

	// --- Journaux ------------------------------------------------------------------------------------------------

	@Test
	void noReceivedValueReachesTheLogsEvenRefusedOrWithAControlCharacter() throws Exception {
		String token = activeToken(this.companyA, CATALOG);
		String marker = remember("g16-marqueur-" + UUID.randomUUID().toString().substring(0, 8));

		invalidParameter(get(CHANGES).param("after", marker), token, "Paramètre invalide : after.");
		invalidParameter(get(CHANGES).param("after", "0").param("limit", marker), token, "Paramètre invalide : limit.");
		invalidParameter(get(CHANGES).param("after", "0").param("limit", "1\u0001" + marker), token, null);
		// Nom de paramètre avec un caractère de contrôle, journaux de Spring au niveau de production (INFO) : le pare-feu le
		// refuse à la lecture des paramètres par la route. En DEBUG, DispatcherServlet lit les paramètres pour journaliser
		// la requête : le refus a lieu avant la route (400 sans corps) et deux journaux de Spring citent le nom (hors G16).
		LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
		List<Logger> requestLoggers = List.of(context.getLogger("org.springframework.test.web.servlet.TestDispatcherServlet"),
				context.getLogger("org.springframework.security.web.firewall.HttpStatusRequestRejectedHandler"));
		List<Level> previous = requestLoggers.stream().map(Logger::getLevel).toList();
		requestLoggers.forEach(logger -> logger.setLevel(Level.INFO));
		try {
			invalidParameter(get(CHANGES).param("after", "0").param("x" + marker + "\u0001", "1"), token, "Paramètre refusé.");
		}
		finally {
			for (int i = 0; i < requestLoggers.size(); i++) {
				requestLoggers.get(i).setLevel(previous.get(i));
			}
		}
		outOfBounds(get(CHANGES).param("after", "-" + "9".repeat(18)), token, "after");
		remember("9".repeat(18));
	}

	// --- Outils --------------------------------------------------------------------------------------------------

	private record Stocked(UUID product, UUID warehouse) {
	}

	/** Produit reçu en stock dans un dépôt de l'entreprise A, sans publication (drapeau baissé). */
	private Stocked stockedProduct(int quantity) {
		this.jdbc.update("update integration_publication set enabled = false");
		UUID actor = this.fixtures.user("g16");
		UUID warehouse = this.fixtures.warehouse("g16");
		UUID product = this.fixtures.product("g16", 1000);
		StockFixtures.runAs(actor, () -> this.stock.applyMovement(StockMovementCommand.of(warehouse, product,
				StockMovementType.RECEIPT, quantity, StockSourceType.MANUAL, "Stock G16")));
		this.actor = actor;
		return new Stocked(product, warehouse);
	}

	private void addWarehouse(Link link, UUID warehouse) {
		this.jdbc.update("insert into integration_link_warehouse (link_id, warehouse_id, added_by) values (?, ?, ?)", link.id(),
				warehouse, this.actor);
	}

	/**
	 * Flux mêlé : CATEGORY (1), AVAILABILITY (2), PRODUCT (3), AVAILABILITY (4), PRODUCT_IMAGE (5), type inconnu (6).
	 * @return objets des événements autres que la disponibilité, dans l'ordre
	 */
	private List<UUID> mixedFeed(UUID product) {
		UUID category = insert(this.companyA, "CATEGORY", "UPSERT", 1, "{}");
		insertFor(product, "AVAILABILITY");
		UUID productEvent = insert(this.companyA, "PRODUCT", "UPSERT", 2, "{}");
		insertFor(product, "AVAILABILITY");
		UUID image = insert(this.companyA, "PRODUCT_IMAGE", "UPSERT", 1, "{}");
		UUID future = insert(this.companyA, "FUTURE_TYPE", "UPSERT", 1, "{}");
		return List.of(category, productEvent, image, future);
	}

	/** Événement mince : aucune charge, version recalculée à la lecture. */
	private void insertFor(UUID product, String type) {
		this.jdbc.update("""
				insert into integration_outbox (type, operation, object_id, object_version, payload)
				values (?, 'UPSERT', ?, 0, null)""", type, product);
	}

	private JsonNode page(String token, long after, int limit) throws Exception {
		return read(this.mvc.perform(bearer(get(CHANGES).param("after", Long.toString(after)).param("limit",
				Integer.toString(limit)), token)).andExpect(status().isOk()));
	}

	private static List<String> types(JsonNode page) {
		List<String> types = new ArrayList<>();
		page.get("items").forEach(item -> types.add(item.get("type").asString()));
		return types;
	}

	private void invalidParameter(MockHttpServletRequestBuilder request, String token, String message) throws Exception {
		ResultActions actions = this.mvc.perform(bearer(request, token))
			.andExpect(status().isBadRequest())
			.andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE))
			.andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
		if (message != null) {
			actions.andExpect(jsonPath("$.message").value(message));
		}
	}

	private void outOfBounds(MockHttpServletRequestBuilder request, String token, String field) throws Exception {
		this.mvc.perform(bearer(request, token))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors.length()").value(1))
			.andExpect(jsonPath("$.errors[0].field").value(field));
	}

	private UUID insert(TenantRef company, String type, String operation, long version, String payload) {
		UUID objectId = UUID.randomUUID();
		TenantContext.runAs(company, () -> this.jdbc.update("""
				insert into integration_outbox (type, operation, object_id, object_version, payload)
				values (?, ?, ?, ?, cast(? as jsonb))""", type, operation, objectId, version, payload));
		return objectId;
	}

	private void resetFeed(TenantRef company) {
		TenantContext.runAs(company, () -> {
			this.jdbc.update("delete from integration_outbox");
			this.jdbc.update("""
					insert into integration_feed_state (id) values (?::uuid)
					on conflict (id) do update set last_seq = 0, purged_through_seq = 0""", FEED_STATE_ID);
		});
	}

	private String activeToken(TenantRef company, Set<String> scopes) {
		Link link = this.links.active(this.links.client(), company.companyId(), "shop-g16-" + UUID.randomUUID(), scopes);
		return token(link, scopes);
	}

	private String token(Link link, Set<String> scopes) {
		String clientId = this.platform.jdbc().sql("select client_id from platform.integration_client where id = :id")
			.param("id", link.clientId()).query(String.class).single();
		return remember(this.tokens.issue(link.id(), clientId, scopes).value());
	}

	private static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder request, String token) {
		return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
	}

	private JsonNode read(ResultActions actions) throws Exception {
		return this.json.readTree(actions.andReturn().getResponse().getContentAsString());
	}

	private static Set<String> fieldNames(JsonNode node) {
		return new HashSet<>(node.propertyNames());
	}

	private static List<UUID> objectIds(JsonNode page) {
		List<UUID> ids = new ArrayList<>();
		page.get("items").forEach(item -> ids.add(UUID.fromString(item.get("objectId").asString())));
		return ids;
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

	/**
	 * Fenêtres fixes d'une minute : un test qui chevauche deux fenêtres verrait son compteur repartir. On attend donc la
	 * fenêtre suivante s'il reste moins de 30 secondes (horloge de PostgreSQL).
	 */
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
		String code = "it-g16-" + UUID.randomUUID().toString().substring(0, 8);
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
