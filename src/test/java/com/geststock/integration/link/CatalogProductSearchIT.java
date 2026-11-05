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
 * {@code GET /catalog/products/search} (lot 3, étape G23 ; contrat {@code catalog:match}) avec deux
 * entreprises réelles : A (l'entreprise de test historique) et B (provisionnée pour ce test, catalogue
 * maîtrisé).
 *
 * <ul>
 * <li>Validation de {@code q} : absent, répété, longueur en points de code Unicode, blancs de bord,
 * caractère de contrôle (G15 étendu aux paramètres de requête texte).</li>
 * <li>Validation de {@code limit} (1–50).</li>
 * <li>Portée {@code catalog:match} obligatoire ; liaison suspendue → 403.</li>
 * <li>Isolation : chaque liaison ne voit que les produits actifs de son entreprise.</li>
 * <li>Motifs ILIKE littéraux : {@code %%} et {@code __} trouvent uniquement les produits contenant ces
 * séquences exactes.</li>
 * <li>Produits archivés absents du résultat.</li>
 * <li>Tri par nom puis par identifiant ; {@code limit} respecté.</li>
 * <li>D1 : champs de réponse identiques au schéma {@code Product} du contrat et à la section
 * {@code products} de l'instantané — même {@code ProductIntegrationEvents.Payload::of}.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "geststock.platform.provisioning.async=false")
@Import(PostgresTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogProductSearchIT {

	private static final String SEARCH = "/api/integration/v1/catalog/products/search";

	private static final String SNAPSHOT = "/api/integration/v1/catalog/snapshot";

	private static final String SUPER_ADMIN = "it_g23_superadmin";

	private static final String SUPER_ADMIN_PASSWORD = "Plateforme-Test-2026";

	private static final Set<String> MATCH = Set.of("catalog:match");

	private static final Set<String> READ = Set.of("catalog:read");

	private static final Set<String> MATCH_AND_READ = Set.of("catalog:match", "catalog:read");

	/** Loggers que la configuration limite parce qu'ils citent les valeurs reçues (G12, G15, revue G16). */
	private static final Set<String> CAPPED_LOGGERS = Set.of("org.postgresql",
			"org.springframework.web.method.HandlerMethod",
			"org.springframework.web.servlet.DispatcherServlet");

	/**
	 * Écriture du corps de réponse par Spring MVC (noms et SKU des produits) : limite assumée (v17-D2,
	 * comme {@code CatalogImageRouteIT}) — ces niveaux ne sont jamais activés en production.
	 */
	private static final String RESPONSE_WRITER =
			"org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor";

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

	private final Map<Logger, Level> levels = new LinkedHashMap<>();

	private ListAppender<ILoggingEvent> logs;

	// Produits créés dans l'entreprise B -----------------------------------------------------------------------

	/** sku = "G23-BY-SKU",   name = "Produit G23 Alpha". */
	private UUID productBySku;

	/** sku = "G23-XYZ-999",  name = "Produit G23 Nommé Par Nom". */
	private UUID productByName;

	/** sku = "G23-SPEC1",    name = "Produit G23 50%% rabais" (contient littéralement "%%"). */
	private UUID productPercent;

	/** sku = "G23-SPEC2",    name = "Produit G23 N__1 test" (contient littéralement "__"). */
	private UUID productUnderscore;

	/** sku = "G23-SORT-A",   name = "Produit G23 sortA aaaa". */
	private UUID productSortA;

	/** sku = "G23-SORT-B",   name = "Produit G23 sortB bbbb". */
	private UUID productSortB;

	/** sku = "G23-SORT-C",   name = "Produit G23 sortC cccc". */
	private UUID productSortC;

	/** sku = "G23-ARCHIVED", name = "Produit G23 Archivé", active = false. */
	private UUID productArchived;

	/** sku = "G23-SPEC3",   name = "Produit G23 barre\\oblique arrière" (contient littéralement "\"). */
	private UUID productBackslash;

	@BeforeAll
	void provisionCompanies() throws Exception {
		this.links = new TestLinks(this.platform, this.registry);
		this.companyA = new TenantRef(this.links.legacyCompany(), "test");
		this.companyB = provisionCompanyB();
		TenantContext.runAs(this.companyB, () -> {
			UUID cat = this.jdbc.queryForObject(
					"insert into category (name) values ('Cat G23') returning id", UUID.class);
			this.productBySku       = product("G23-BY-SKU",    "Produit G23 Alpha",        cat, 2000);
			this.productByName      = product("G23-XYZ-999",   "Produit G23 Nommé Par Nom", cat, 3500);
			this.productPercent     = product("G23-SPEC1",     "Produit G23 50%% rabais",   cat, 1000);
			this.productUnderscore  = product("G23-SPEC2",     "Produit G23 N__1 test",     cat, 1500);
			this.productSortA       = product("G23-SORT-A",    "Produit G23 sortA aaaa",    cat, 500);
			this.productSortB       = product("G23-SORT-B",    "Produit G23 sortB bbbb",    cat, 600);
			this.productSortC       = product("G23-SORT-C",    "Produit G23 sortC cccc",    cat, 700);
			UUID archived           = product("G23-ARCHIVED",  "Produit G23 Archivé",       cat, 800);
			this.jdbc.update("update product set active = false where id = ?", archived);
			this.productArchived = archived;
			this.productBackslash = product("G23-SPEC3",     "Produit G23 barre\\oblique arrière", cat, 950);
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
		((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
				.detachAppender(this.logs);
		this.levels.forEach(Logger::setLevel);
		this.levels.clear();
		try {
			List<String> messages = this.logs.list.stream()
					.filter(event -> !RESPONSE_WRITER.equals(event.getLoggerName()))
					.map(CatalogProductSearchIT::render)
					.toList();
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

	// --- Validation des paramètres -------------------------------------------------------------------------------

	@Test
	void qAbsentOrRepeatedIsInvalidParameter() throws Exception {
		Linked linked = active(this.companyB, MATCH);
		String token = tokenOf(linked, MATCH);
		// absent
		bearer(get(SEARCH), token)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
		// répété
		this.mvc.perform(get(SEARCH)
				.param("q", "ab", "cd")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
	}

	@Test
	void qLengthInUnicodeCodePointsIsCheckedAfterStrip() throws Exception {
		Linked linked = active(this.companyB, MATCH);
		String token = tokenOf(linked, MATCH);
		// espaces seuls → après strip = "" → 0 < 2 : VALIDATION_FAILED
		bearer(get(SEARCH).param("q", "   "), token)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("q"));
		// 1 point de code après strip : VALIDATION_FAILED
		bearer(get(SEARCH).param("q", "x"), token)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("q"));
		// 1 point de code après strip des espaces encadrants : VALIDATION_FAILED
		bearer(get(SEARCH).param("q", "  x  "), token)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("q"));
		// 2 points de code (minimum) : 200 OK
		bearer(get(SEARCH).param("q", "ab"), token)
				.andExpect(status().isOk());
		// 100 points de code (maximum) : 200 OK
		bearer(get(SEARCH).param("q", "x".repeat(100)), token)
				.andExpect(status().isOk());
		// 101 points de code : VALIDATION_FAILED
		bearer(get(SEARCH).param("q", "x".repeat(101)), token)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("q"));
		// 1 emoji = 1 point de code Unicode, mais 2 unités UTF-16 (char en Java) :
		// codePointCount(0, q.length()) = 1 → 400 ; q.length() = 2 donnerait 200 à tort.
		bearer(get(SEARCH).param("q", "\uD83D\uDE00"), token)  // U+1F600 😀, 2 chars Java
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("q"));
		// 2 emojis = 2 points de code : minimum respecté → 200 OK
		bearer(get(SEARCH).param("q", "\uD83D\uDE00\uD83D\uDE00"), token)
				.andExpect(status().isOk());
	}

	@Test
	void limitBoundsAreChecked() throws Exception {
		Linked linked = active(this.companyB, MATCH);
		String token = tokenOf(linked, MATCH);
		// 0 → VALIDATION_FAILED
		bearer(get(SEARCH).param("q", "G23").param("limit", "0"), token)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("limit"));
		// 51 → VALIDATION_FAILED
		bearer(get(SEARCH).param("q", "G23").param("limit", "51"), token)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("limit"));
		// 1 (minimum) → 200 OK
		bearer(get(SEARCH).param("q", "G23").param("limit", "1"), token)
				.andExpect(status().isOk());
		// 50 (maximum) → 200 OK
		bearer(get(SEARCH).param("q", "G23").param("limit", "50"), token)
				.andExpect(status().isOk());
	}

	// --- Autorisation --------------------------------------------------------------------------------------------

	@Test
	void insufficientScopeAndSuspendedLinkAreForbiddenAndNoTokenIsUnauthorized() throws Exception {
		// portée insuffisante : catalog:read sans catalog:match
		bearer(get(SEARCH).param("q", "G23"), tokenOf(active(this.companyB, READ), READ))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("INSUFFICIENT_SCOPE"));
		// liaison suspendue
		Linked linked = active(this.companyB, MATCH);
		String token = tokenOf(linked, MATCH);
		this.registry.suspend(linked.id(), SuspensionReason.MANUAL);
		bearer(get(SEARCH).param("q", "G23"), token)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("LINK_SUSPENDED"));
		// sans jeton
		this.mvc.perform(get(SEARCH).param("q", "G23"))
				.andExpect(status().isUnauthorized());
	}

	// --- Résultats -----------------------------------------------------------------------------------------------

	@Test
	void eachLinkSeesOnlyProductsOfItsOwnCompany() throws Exception {
		// liaison de A : ne voit pas les produits de B
		String tokenA = tokenOf(active(this.companyA, MATCH), MATCH);
		for (String id : ids(search(tokenA, "G23-BY-SKU", null))) {
			assertThat(id).as("produit de B non visible par la liaison de A")
					.isNotEqualTo(this.productBySku.toString());
		}
	}

	@Test
	void searchBySkuAndByNameBothWorkCaseInsensitively() throws Exception {
		String token = tokenOf(active(this.companyB, MATCH), MATCH);
		// par sku, casse exacte
		assertThat(ids(search(token, "G23-BY-SKU", null))).contains(this.productBySku.toString());
		// par sku, minuscules
		assertThat(ids(search(token, "g23-by-sku", null))).contains(this.productBySku.toString());
		// par fragment du nom
		assertThat(ids(search(token, "Nommé Par Nom", null))).contains(this.productByName.toString());
		// par fragment du nom en minuscules
		assertThat(ids(search(token, "nommé par nom", null))).contains(this.productByName.toString());
	}

	@Test
	void literalPercentUnderscoreAndBackslashAreEscapedInTheIlikePattern() throws Exception {
		String token = tokenOf(active(this.companyB, MATCH), MATCH);
		// "%%" : seul productPercent a "%%" dans son nom
		List<String> resultPercent = ids(search(token, "%%", null));
		assertThat(resultPercent).containsExactly(this.productPercent.toString());
		// "__" : seul productUnderscore a "__" dans son nom
		List<String> resultUnderscore = ids(search(token, "__", null));
		assertThat(resultUnderscore).containsExactly(this.productUnderscore.toString());
		// "barre\" (backslash littéral) : seul productBackslash a "barre\" dans son nom
		// (motif ILIKE %barre\\% avec ESCAPE '\' ; sans échappement : %barre\% = %barre%
		// trouverait des produits sans backslash, ou \% deviendrait un "%" littéral)
		List<String> resultBackslash = ids(search(token, "barre\\", null));
		assertThat(resultBackslash).containsExactly(this.productBackslash.toString());
	}

	@Test
	void archivedProductIsNotReturned() throws Exception {
		String token = tokenOf(active(this.companyB, MATCH), MATCH);
		List<String> result = ids(search(token, "G23-ARCHIVED", null));
		assertThat(result).as("produit archivé absent du résultat").doesNotContain(this.productArchived.toString());
	}

	@Test
	void limitIsRespectedAndResultsAreSortedByNameThenById() throws Exception {
		String token = tokenOf(active(this.companyB, MATCH), MATCH);
		// 3 produits correspondent à "G23-SORT" par sku (G23-SORT-A, B, C)
		JsonNode full = search(token, "G23-SORT", null);
		List<String> sortedIds = List.of(
				this.productSortA.toString(), this.productSortB.toString(), this.productSortC.toString());
		assertThat(ids(full)).as("les 3 produits G23-SORT dans le résultat").containsAll(sortedIds);
		// ordre par nom : sortA < sortB < sortC
		List<String> names = new ArrayList<>();
		for (JsonNode item : full.get("items")) {
			String id = item.get("id").asString();
			if (sortedIds.contains(id)) {
				names.add(item.get("name").asString());
			}
		}
		assertThat(names).as("triés par nom").isSortedAccordingTo(String::compareTo);
		// limit=2 : seulement 2 résultats parmi les 3
		JsonNode limited = search(token, "G23-SORT", 2);
		assertThat(limited.get("items")).hasSize(2);
	}

	@Test
	void responseSchemaMatchesContractAndSnapshotForSameProduct() throws Exception {
		// D1 : format identique à l'instantané — même ProductIntegrationEvents.Payload::of
		Linked linked = active(this.companyB, MATCH_AND_READ);
		String searchToken = tokenOf(linked, MATCH);
		String snapshotToken = tokenOf(linked, READ);

		JsonNode fromSearch = findById(search(searchToken, "G23-BY-SKU", null).get("items"), this.productBySku);
		assertThat(fromSearch).as("produit trouvé par recherche").isNotNull();

		// champs obligatoires du schéma Product
		assertThat(fromSearch.get("id").asString()).isEqualTo(this.productBySku.toString());
		assertThat(fromSearch.get("sku").asString()).isEqualTo("G23-BY-SKU");
		assertThat(fromSearch.get("name").asString()).isEqualTo("Produit G23 Alpha");
		assertThat(fromSearch.get("referencePrice").asString()).matches("[0-9]+\\.[0-9]{2}");
		assertThat(fromSearch.get("active").asBoolean()).isTrue();
		assertThat(fromSearch.has("version")).isTrue();
		assertThat(fromSearch.has("categoryId")).isTrue();
		assertThat(fromSearch.has("unit")).isTrue();

		// D1 : identique à l'instantané
		JsonNode fromSnapshot = productFromSnapshot(snapshotToken, this.productBySku);
		assertThat(fromSnapshot).as("D1 : produit trouvé dans l'instantané").isNotNull();
		assertThat(fromSearch).as("D1 : même représentation que l'instantané").isEqualTo(fromSnapshot);
	}

	@Test
	void controlCharacterInQIsRefusedAndDoesNotReachLogs() throws Exception {
		// Marqueur unique ; tearDown vérifie qu'il n'apparaît dans aucun journal.
		String marker = remember("g23ctrlmarker" + UUID.randomUUID().toString().substring(0, 8));
		// Caractère de contrôle U+001B (ESC) incorporé dans q
		String q = marker + "\u001B";
		String token = tokenOf(active(this.companyB, MATCH), MATCH);
		this.mvc.perform(get(SEARCH)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.param("q", q))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		// tearDown : marker ne doit figurer dans aucun journal (hors RESPONSE_WRITER)
	}

	// --- Outils ------------------------------------------------------------------------------------------------

	private record Linked(UUID id, String clientId) {
	}

	private UUID product(String sku, String name, UUID category, int retailPrice) {
		return this.jdbc.queryForObject(
				"insert into product (sku, name, category_id, retail_price) values (?, ?, ?, ?) returning id",
				UUID.class, sku, name, category, retailPrice);
	}

	private Linked active(TenantRef company, Set<String> scopes) {
		Link link = this.links.active(this.links.client(), company.companyId(), "shop-g23-" + UUID.randomUUID(), scopes);
		String clientId = this.platform.jdbc().sql("select client_id from platform.integration_client where id = :id")
				.param("id", link.clientId()).query(String.class).single();
		return new Linked(link.id(), clientId);
	}

	private String tokenOf(Linked linked, Set<String> scopes) {
		return remember(this.tokens.issue(linked.id(), linked.clientId(), scopes).value());
	}

	/** Appelle {@code GET /catalog/products/search} et retourne le corps 200. */
	private JsonNode search(String token, String q, Integer limit) throws Exception {
		MockHttpServletRequestBuilder request = get(SEARCH).param("q", q);
		if (limit != null) {
			request.param("limit", limit.toString());
		}
		String body = bearer(request, token).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return this.json.readTree(body);
	}

	/** Parcourt les pages de l'instantané jusqu'à trouver le produit ; {@code null} s'il est absent. */
	private JsonNode productFromSnapshot(String token, UUID productId) throws Exception {
		String next = null;
		for (int page = 0; page < 50; page++) {
			MockHttpServletRequestBuilder request = get(SNAPSHOT).param("limit", "50");
			if (next != null) {
				request.param("pageToken", next);
			}
			String body = bearer(request, token).andExpect(status().isOk())
					.andReturn().getResponse().getContentAsString();
			JsonNode snap = this.json.readTree(body);
			for (JsonNode p : snap.get("products")) {
				if (p.get("id").asString().equals(productId.toString())) {
					return p;
				}
			}
			if (!snap.has("nextPageToken")) {
				break;
			}
			next = snap.get("nextPageToken").asString();
		}
		return null;
	}

	private static List<String> ids(JsonNode result) {
		List<String> ids = new ArrayList<>();
		for (JsonNode item : result.get("items")) {
			ids.add(item.get("id").asString());
		}
		return ids;
	}

	private static JsonNode findById(JsonNode items, UUID id) {
		for (JsonNode item : items) {
			if (item.get("id").asString().equals(id.toString())) {
				return item;
			}
		}
		return null;
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

	private TenantRef provisionCompanyB() throws Exception {
		this.platform.jdbc().sql("delete from platform.platform_admin where username = :u").param("u", SUPER_ADMIN).update();
		this.platform.jdbc()
				.sql("insert into platform.platform_admin (username, full_name, password_hash, must_change_password) values (:u, 'IT', :h, false)")
				.param("u", SUPER_ADMIN).param("h", this.passwordEncoder.encode(SUPER_ADMIN_PASSWORD)).update();
		String login = this.mvc.perform(post("/api/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\": \"" + SUPER_ADMIN + "\", \"password\": \"" + SUPER_ADMIN_PASSWORD + "\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String superToken = this.json.readTree(login).get("accessToken").asString();
		String code = "it-g23-" + UUID.randomUUID().toString().substring(0, 8);
		String created = this.mvc.perform(post("/api/platform/companies")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + superToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"code": "%s", "legalName": "Commerce B G23 SARL", "tradeName": "Commerce B G23",
						 "ownerLastName": "Kouassi", "adminUsername": "admin"}""".formatted(code)))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ACTIVE"))
				.andReturn().getResponse().getContentAsString();
		UUID id = UUID.fromString(this.json.readTree(created).get("id").asString());
		this.createdCompanies.add(id);
		return new TenantRef(id, code);
	}

}
