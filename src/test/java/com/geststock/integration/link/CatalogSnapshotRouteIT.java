package com.geststock.integration.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.geststock.integration.SnapshotExpiredException;
import com.geststock.integration.SnapshotService;
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
 * {@code GET /catalog/snapshot} (lot 3, étape G18 ; contrat {@code getCatalogSnapshot}, v11-D10, v17-D1) avec deux
 * entreprises réelles : A (l'entreprise de test historique) et B (provisionnée pour ce test, catalogue maîtrisé).
 * Portée {@code catalog:read} ; disponibilité seulement avec {@code stock:read} : sans elle, le champ
 * {@code availability} est <b>absent</b> de chaque page et l'instantané s'arrête après {@code productImages} ; un jeton
 * placé sur la section de disponibilité, présenté sans {@code stock:read}, reçoit une dernière page vide (décisions
 * du 2026-11-03). 60 appels par minute et par liaison ; aucune valeur reçue dans les journaux.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "geststock.platform.provisioning.async=false")
@Import(PostgresTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogSnapshotRouteIT {

	private static final String SNAPSHOT = "/api/integration/v1/catalog/snapshot";

	private static final String CHANGES = "/api/integration/v1/catalog/changes";

	private static final String SUPER_ADMIN = "it_g18_superadmin";

	private static final String SUPER_ADMIN_PASSWORD = "Plateforme-Test-2026";

	private static final Set<String> CATALOG = Set.of("catalog:read");

	private static final Set<String> CATALOG_AND_STOCK = Set.of("catalog:read", "stock:read");

	/** Loggers que la configuration limite parce qu'ils citent les valeurs reçues (G12, G15, revue G16). */
	private static final Set<String> CAPPED_LOGGERS = Set.of("org.postgresql", "org.springframework.web.method.HandlerMethod",
			"org.springframework.web.servlet.DispatcherServlet");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private PlatformDb platform;

	@Autowired
	private LinkRegistry registry;

	@Autowired
	private IntegrationTokenService tokens;

	/** Espion : expiration d'un jeton (24 h) simulée sur un seul appel ; la vraie règle est couverte par SnapshotServiceIT. */
	@MockitoSpyBean
	private SnapshotService snapshots;

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

	private final Map<Logger, Level> levels = new LinkedHashMap<>();

	private ListAppender<ILoggingEvent> logs;

	@BeforeAll
	void provisionCompanies() throws Exception {
		this.links = new TestLinks(this.platform, this.registry);
		this.companyA = new TenantRef(this.links.legacyCompany(), "test");
		this.companyB = provisionCompanyB();
		// Catalogue de B : une catégorie et deux produits de plus que ceux créés par le provisionnement.
		TenantContext.runAs(this.companyB, () -> {
			UUID category = this.jdbc.queryForObject("insert into category (name) values ('Catégorie G18') returning id", UUID.class);
			for (int i = 1; i <= 2; i++) {
				this.jdbc.update("""
						insert into product (sku, name, category_id, purchase_price, retail_price, wholesale_price)
						values (?, ?, ?, 1000, 2000, 1800)""", "G18-" + i, "Produit G18 " + i, category);
			}
		});
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
			List<String> messages = this.logs.list.stream().map(CatalogSnapshotRouteIT::render).toList();
			assertThat(messages).as("aucune valeur reçue dans les journaux")
				.allSatisfy(message -> this.sensitive.forEach(value -> assertThat(message).doesNotContain(value)));
		}
		finally {
			this.sensitive.clear();
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

	// --- Pages et sections ---------------------------------------------------------------------------------------

	@Test
	void withCatalogReadOnlyNoPageHasAnAvailabilityFieldAndTheSnapshotEndsAfterTheImages() throws Exception {
		Counts expected = counts(this.companyB);
		String token = tokenOf(active(this.companyB, CATALOG_AND_STOCK), CATALOG);

		List<JsonNode> pages = allPages(token, 1);

		long asOfSeq = pages.getFirst().get("asOfSeq").asLong();
		for (JsonNode page : pages) {
			assertThat(page.has("availability")).as("champ availability absent sans stock:read").isFalse();
			assertThat(page.get("asOfSeq").asLong()).as("asOfSeq identique sur toutes les pages").isEqualTo(asOfSeq);
			assertThat(page.get("currency").asString()).isEqualTo("XOF");
		}
		assertThat(pages.getLast().has("nextPageToken")).isFalse();
		// Fin après productImages : une page par objet servi (limit 1), plus au plus une dernière page vide ; aucune page
		// pour une disponibilité calculée puis masquée.
		assertThat(pages).as("l'instantané s'arrête après productImages")
			.hasSizeLessThanOrEqualTo(expected.categories() + 2 * expected.products() + 1);
		assertThat(total(pages, "categories")).isEqualTo(expected.categories());
		assertThat(total(pages, "products")).isEqualTo(expected.products());
		assertThat(total(pages, "productImages")).isEqualTo(expected.products());
		assertThat(pages.stream().map(JsonNode::toString)).as("aucune quantité")
			.noneMatch(body -> body.contains("perWarehouse") || body.contains("\"available\""));
	}

	@Test
	void withStockReadEveryPageHasTheAvailabilityFieldAndEveryProductIsCovered() throws Exception {
		Counts expected = counts(this.companyB);
		String token = tokenOf(active(this.companyB, CATALOG_AND_STOCK), CATALOG_AND_STOCK);

		List<JsonNode> pages = allPages(token, 2);

		for (JsonNode page : pages) {
			assertThat(page.has("availability")).as("champ availability présent avec stock:read").isTrue();
			assertThat(page.get("availability").isArray()).isTrue();
		}
		assertThat(total(pages, "availability")).isEqualTo(expected.products());
		assertThat(pages.getLast().has("nextPageToken")).isFalse();
	}

	@Test
	void aTokenPositionedOnTheAvailabilityGivesAnEmptyLastPageWithoutStockRead() throws Exception {
		Counts expected = counts(this.companyB);
		Linked linked = active(this.companyB, CATALOG_AND_STOCK);
		String withStock = tokenOf(linked, CATALOG_AND_STOCK);
		String withoutStock = tokenOf(linked, CATALOG);

		// Première page : toutes les catégories, produits et images, puis une seule disponibilité.
		int limit = expected.categories() + 2 * expected.products() + 1;
		JsonNode first = page(withStock, null, limit);
		assertThat(first.get("availability")).hasSize(1);
		String next = first.get("nextPageToken").asString();

		JsonNode last = page(withoutStock, next, 500);
		assertThat(last.has("availability")).isFalse();
		assertThat(last.has("nextPageToken")).as("dernière page").isFalse();
		assertThat(last.get("categories")).isEmpty();
		assertThat(last.get("products")).isEmpty();
		assertThat(last.get("productImages")).isEmpty();
		assertThat(last.get("asOfSeq").asLong()).isEqualTo(first.get("asOfSeq").asLong());
	}

	@Test
	void theLinkReadsTheCatalogOfItsCompanyOnlyAndTheFeedResumesAfterAsOfSeq() throws Exception {
		String tokenA = tokenOf(active(this.companyA, CATALOG), CATALOG);
		String tokenB = tokenOf(active(this.companyB, CATALOG), CATALOG);
		Set<String> categoriesOfB = ids(allPages(tokenB, 500), "categories");
		Set<String> categoriesOfA = ids(allPages(tokenA, 500), "categories");

		assertThat(categoriesOfA).as("aucune catégorie de B chez A").doesNotContainAnyElementsOf(categoriesOfB);

		JsonNode first = page(tokenB, null, 500);
		this.mvc.perform(get(CHANGES).param("after", first.get("asOfSeq").asString())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB)).andExpect(status().isOk());
	}

	// --- Jeton de page et paramètres ---------------------------------------------------------------------------

	@Test
	void aTokenOfAnotherLinkATamperedOrOverlongTokenIsAnInvalidPageToken() throws Exception {
		String mine = tokenOf(active(this.companyB, CATALOG), CATALOG);
		String other = tokenOf(active(this.companyB, CATALOG), CATALOG);
		String otherNext = page(other, null, 1).get("nextPageToken").asString();
		String tampered = remember(otherNext.substring(0, otherNext.length() - 2)
				+ (otherNext.endsWith("A") ? "BB" : "AA"));

		invalidPageToken(mine, otherNext);
		invalidPageToken(mine, tampered);
		invalidPageToken(mine, remember("x".repeat(513)));
		invalidPageToken(mine, "");
	}

	@Test
	void anExpiredTokenIsGone() throws Exception {
		String token = tokenOf(active(this.companyB, CATALOG), CATALOG);
		String marker = remember("jeton-expire-g18");
		doThrow(new SnapshotExpiredException()).when(this.snapshots).read(any(), eq(marker), any(), anyBoolean());

		bearer(get(SNAPSHOT).param("pageToken", marker), token)
			.andExpect(status().isGone())
			.andExpect(jsonPath("$.code").value("SNAPSHOT_EXPIRED"));
	}

	@Test
	void aRepeatedOrNonNumericParameterIsInvalidAndALimitOutOfBoundsIsAValidationFailure() throws Exception {
		String token = tokenOf(active(this.companyB, CATALOG), CATALOG);

		invalidParameter(get(SNAPSHOT).param("pageToken", "a", "b"), token, "Paramètre répété : pageToken.");
		invalidParameter(get(SNAPSHOT).param("limit", "abc"), token, "Paramètre invalide : limit.");
		invalidParameter(get(SNAPSHOT).param("limit", ""), token, "Paramètre invalide : limit.");
		invalidParameter(get(SNAPSHOT).param("limit", "1", "2"), token, "Paramètre répété : limit.");
		for (String limit : List.of("0", "501")) {
			bearer(get(SNAPSHOT).param("limit", limit), token)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("limit"));
		}
		bearer(get(SNAPSHOT).param("limit", "500"), token).andExpect(status().isOk());
	}

	// --- Autorisation et débit -----------------------------------------------------------------------------------

	@Test
	void withoutCatalogReadTheRouteIsForbiddenAndASuspendedLinkIsRefused() throws Exception {
		bearer(get(SNAPSHOT), tokenOf(active(this.companyB, Set.of("stock:read")), Set.of("stock:read")))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("INSUFFICIENT_SCOPE"));

		Linked suspended = active(this.companyB, CATALOG);
		String token = tokenOf(suspended, CATALOG);
		this.registry.suspend(suspended.id(), SuspensionReason.MANUAL);
		bearer(get(SNAPSHOT), token).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("LINK_SUSPENDED"));

		this.mvc.perform(get(SNAPSHOT)).andExpect(status().isUnauthorized());
	}

	@Test
	void theSixtyFirstCallOfTheLinkIsRefusedWithItsOwnCounter() throws Exception {
		Linked linked = active(this.companyB, CATALOG);
		String token = tokenOf(linked, CATALOG);
		String other = tokenOf(active(this.companyB, CATALOG), CATALOG);
		awaitFreshWindow();

		for (int i = 0; i < 60; i++) {
			bearer(get(SNAPSHOT).param("limit", "1"), token).andExpect(status().isOk());
		}
		bearer(get(SNAPSHOT), token)
			.andExpect(status().isTooManyRequests())
			.andExpect(header().exists(HttpHeaders.RETRY_AFTER))
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
			.andExpect(jsonPath("$.code").value("RATE_LIMITED"));
		// Compteur propre à l'instantané : le flux de la même liaison n'est pas touché.
		bearer(get(CHANGES).param("after", "0"), token).andExpect(status().isOk());
		bearer(get(SNAPSHOT), other).andExpect(status().isOk());
	}

	@Test
	void noReceivedValueReachesTheLogs() throws Exception {
		String token = tokenOf(active(this.companyB, CATALOG), CATALOG);
		String marker = remember("g18marqueur" + UUID.randomUUID().toString().substring(0, 8));

		invalidPageToken(token, marker);
		invalidParameter(get(SNAPSHOT).param("limit", marker), token, "Paramètre invalide : limit.");
	}

	// --- Outils ------------------------------------------------------------------------------------------------

	private record Linked(UUID id, String clientId) {
	}

	private record Counts(int categories, int products) {
	}

	private Counts counts(TenantRef company) {
		return TenantContext.callAs(company, () -> new Counts(
				this.jdbc.queryForObject("select count(*) from category", Integer.class),
				this.jdbc.queryForObject("select count(*) from product", Integer.class)));
	}

	private Linked active(TenantRef company, Set<String> scopes) {
		Link link = this.links.active(this.links.client(), company.companyId(), "shop-g18-" + UUID.randomUUID(), scopes);
		String clientId = this.platform.jdbc().sql("select client_id from platform.integration_client where id = :id")
			.param("id", link.clientId()).query(String.class).single();
		return new Linked(link.id(), clientId);
	}

	/** Jeton de la liaison, limité aux portées demandées (portées effectives = jeton ∩ liaison). */
	private String tokenOf(Linked linked, Set<String> scopes) {
		return remember(this.tokens.issue(linked.id(), linked.clientId(), scopes).value());
	}

	private List<JsonNode> allPages(String token, int limit) throws Exception {
		List<JsonNode> pages = new ArrayList<>();
		String next = null;
		do {
			JsonNode page = page(token, next, limit);
			pages.add(page);
			next = page.has("nextPageToken") ? page.get("nextPageToken").asString() : null;
			assertThat(pages).as("pagination finie").hasSizeLessThan(50);
		}
		while (next != null);
		return pages;
	}

	private JsonNode page(String token, String pageToken, int limit) throws Exception {
		MockHttpServletRequestBuilder request = get(SNAPSHOT).param("limit", Integer.toString(limit));
		if (pageToken != null) {
			request.param("pageToken", pageToken);
		}
		String body = bearer(request, token).andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
			.andReturn().getResponse().getContentAsString();
		return this.json.readTree(body);
	}

	private static int total(List<JsonNode> pages, String section) {
		return pages.stream().mapToInt(page -> page.get(section).size()).sum();
	}

	private static Set<String> ids(List<JsonNode> pages, String section) {
		Set<String> ids = new HashSet<>();
		pages.forEach(page -> page.get(section).forEach(item -> ids.add(item.get("id").asString())));
		return ids;
	}

	private void invalidPageToken(String token, String pageToken) throws Exception {
		bearer(get(SNAPSHOT).param("pageToken", pageToken), token)
			.andExpect(status().isBadRequest())
			.andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE))
			.andExpect(jsonPath("$.code").value("INVALID_PAGE_TOKEN"));
	}

	private void invalidParameter(MockHttpServletRequestBuilder request, String token, String message) throws Exception {
		bearer(request, token)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
			.andExpect(jsonPath("$.message").value(message));
	}

	private ResultActions bearer(MockHttpServletRequestBuilder request, String token) throws Exception {
		return this.mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
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
		String code = "it-g18-" + UUID.randomUUID().toString().substring(0, 8);
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
