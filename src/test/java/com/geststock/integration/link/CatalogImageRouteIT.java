package com.geststock.integration.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.imageio.ImageIO;

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
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /catalog/products/{productId}/image} (lot 3, étape G19 ; décisions G19-D1 à G19-D9 du 2026-11-03) avec deux
 * entreprises réelles : A (l'entreprise de test historique) et B (provisionnée pour ce test). JPEG binaire,
 * {@code ETag} = empreinte SHA-256 et {@code 304}, {@code Cache-Control: no-store}, un seul {@code 404}
 * ({@code PRODUCT_IMAGE_NOT_FOUND}) pour un produit inexistant, d'une autre entreprise ou sans image, image servie pour
 * un produit archivé, identifiant non canonique refusé sans citer la valeur, 120 appels par minute et par liaison
 * ({@code 304} compris), erreurs en {@code application/problem+json} quel que soit {@code Accept}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "geststock.platform.provisioning.async=false")
@Import(PostgresTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogImageRouteIT {

	private static final String IMAGE = "/api/integration/v1/catalog/products/{productId}/image";

	private static final String SNAPSHOT = "/api/integration/v1/catalog/snapshot";

	private static final String SUPER_ADMIN = "it_g19_superadmin";

	private static final String SUPER_ADMIN_PASSWORD = "Plateforme-Test-2026";

	private static final Set<String> CATALOG = Set.of("catalog:read");

	/** Loggers que la configuration limite parce qu'ils citent les valeurs reçues (G12, G15, revue G16). */
	private static final Set<String> CAPPED_LOGGERS = Set.of("org.postgresql", "org.springframework.web.method.HandlerMethod",
			"org.springframework.web.servlet.DispatcherServlet");

	/**
	 * Loggers de Spring qui, en DEBUG ou TRACE, écrivent le chemin de toute requête (ici l'identifiant de produit) :
	 * ligne de requête de Spring Security, et corps de réponse écrit par Spring MVC (le champ {@code path} d'une
	 * {@code ApiError} renvoie au client son propre chemin, RFC 9457). Limite assumée (v17-D2) : ces niveaux ne sont
	 * jamais activés en production. Leur contenu n'est pas vérifié ; tous les autres journaux le sont, dont celui de la
	 * chaîne d'intégration (modèle de route, jamais le chemin reçu).
	 */
	private static final Set<String> REQUEST_LINE_LOGGERS = Set.of("org.springframework.security.web.FilterChainProxy",
			"org.springframework.security.web.access.intercept.RequestMatcherDelegatingAuthorizationManager",
			"org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor");

	/** Remplaçant de {@code DispatcherServlet} sous MockMvc : limité à INFO comme lui en production (revue G16). */
	private static final String TEST_DISPATCHER = "org.springframework.test.web.servlet.TestDispatcherServlet";

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

	private final List<UUID> productsOfA = new ArrayList<>();

	private final List<String> sensitive = java.util.Collections.synchronizedList(new ArrayList<>());

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
		Logger dispatcher = context.getLogger(TEST_DISPATCHER);
		this.levels.putIfAbsent(dispatcher, dispatcher.getLevel());
		dispatcher.setLevel(Level.INFO);
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
			List<String> messages = this.logs.list.stream()
				.filter(event -> !REQUEST_LINE_LOGGERS.contains(event.getLoggerName()))
				.map(CatalogImageRouteIT::render)
				.toList();
			assertThat(messages).as("aucune valeur reçue dans les journaux")
				.allSatisfy(message -> this.sensitive.forEach(value -> assertThat(message).doesNotContain(value)));
		}
		finally {
			this.sensitive.clear();
			this.links.cleanUp();
			TenantContext.runAs(this.companyA, () -> {
				for (UUID product : this.productsOfA) {
					this.jdbc.update("delete from product where id = ?", product);
				}
			});
			this.productsOfA.clear();
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

	// --- Image servie et validation (G19-D1, G19-D2, G19-D8) ----------------------------------------------------

	@Test
	void theImageIsServedAsJpegWithItsFingerprintAsETagAndWithoutCache() throws Exception {
		String token = token(this.companyA);
		byte[] jpeg = jpeg(Color.RED);
		UUID product = productWithImage(this.companyA, jpeg);

		image(token, product).andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_JPEG_VALUE))
			.andExpect(header().string(HttpHeaders.ETAG, "\"" + sha256(jpeg) + "\""))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
			.andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, jpeg.length))
			.andExpect(content().bytes(jpeg));
	}

	@Test
	void aMatchingIfNoneMatchGivesANotModifiedWithoutBody() throws Exception {
		String token = token(this.companyA);
		byte[] jpeg = jpeg(Color.BLUE);
		UUID product = productWithImage(this.companyA, jpeg);
		String etag = "\"" + sha256(jpeg) + "\"";

		for (String ifNoneMatch : List.of(etag, "\"autre\", " + etag, "W/" + etag)) {
			image(token, product, ifNoneMatch).andExpect(status().isNotModified())
				.andExpect(header().string(HttpHeaders.ETAG, etag))
				.andExpect(content().bytes(new byte[0]));
		}
		image(token, product, "\"" + "0".repeat(64) + "\"").andExpect(status().isOk()).andExpect(content().bytes(jpeg));
		// « * » : pas de 304 avec le mécanisme standard de Spring MVC (comme la route de l'API principale) ; documenté
		// dans le contrat, le client envoie l'empreinte qu'il détient.
		image(token, product, "*").andExpect(status().isOk()).andExpect(content().bytes(jpeg));
	}

	@Test
	void aReplacedImageHasANewETagAndTheOldOneNoLongerMatches() throws Exception {
		String token = token(this.companyA);
		byte[] first = jpeg(Color.GREEN);
		UUID product = productWithImage(this.companyA, first);
		byte[] second = jpeg(Color.YELLOW);
		TenantContext.runAs(this.companyA, () -> this.jdbc.update(
				"update product_image set data = ?, size_bytes = ?, sha256 = ?, updated_at = now() where product_id = ?",
				second, second.length, sha256(second), product));

		image(token, product, "\"" + sha256(first) + "\"").andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ETAG, "\"" + sha256(second) + "\""))
			.andExpect(content().bytes(second));
	}

	@Test
	void theImageOfAnArchivedProductIsServed() throws Exception {
		String token = token(this.companyA);
		byte[] jpeg = jpeg(Color.ORANGE);
		UUID product = productWithImage(this.companyA, jpeg);
		TenantContext.runAs(this.companyA, () -> this.jdbc.update("update product set active = false where id = ?", product));

		image(token, product).andExpect(status().isOk()).andExpect(content().bytes(jpeg));
	}

	// --- Un seul 404 (G19-D3) et isolation ------------------------------------------------------------------------

	@Test
	void anUnknownProductAProductOfAnotherCompanyAndAProductWithoutImageGetTheSameNotFound() throws Exception {
		String tokenA = token(this.companyA);
		UUID ofB = productWithImage(this.companyB, jpeg(Color.MAGENTA));
		UUID withoutImage = product(this.companyA);

		String unknown = notFound(tokenA, UUID.randomUUID());
		assertThat(notFound(tokenA, ofB)).as("produit de B, vu par A").isEqualTo(unknown);
		assertThat(notFound(tokenA, withoutImage)).as("produit sans image").isEqualTo(unknown);
		// Témoin : la liaison de B lit l'image de son produit.
		image(token(this.companyB), ofB).andExpect(status().isOk());
	}

	// --- Identifiant (G19-D5) -------------------------------------------------------------------------------------

	@Test
	void aNonCanonicalProductIdIsAnInvalidParameterWithoutTheValue() throws Exception {
		String token = token(this.companyA);
		String marker = remember("g19marqueur" + UUID.randomUUID().toString().substring(0, 6));

		for (String id : List.of(marker, "1-1-1-1-1", "0" + UUID.randomUUID(), UUID.randomUUID().toString().replace("-", ""),
				"{" + UUID.randomUUID() + "}")) {
			this.mvc.perform(get(IMAGE, id).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isBadRequest())
				.andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE))
				.andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
				.andExpect(jsonPath("$.message").value("Paramètre invalide : productId."));
		}
		// Forme canonique en majuscules : acceptée (hexadécimal insensible à la casse).
		byte[] jpeg = jpeg(Color.CYAN);
		UUID product = productWithImage(this.companyA, jpeg);
		this.mvc.perform(get(IMAGE, product.toString().toUpperCase()).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk()).andExpect(content().bytes(jpeg));
	}

	// --- Accept (G19-D7) -------------------------------------------------------------------------------------------

	@Test
	void errorsAreProblemJsonEvenWhenTheClientAcceptsOnlyJpeg() throws Exception {
		String token = token(this.companyA);

		this.mvc.perform(get(IMAGE, UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.accept(MediaType.IMAGE_JPEG))
			.andExpect(status().isNotFound())
			.andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE))
			.andExpect(jsonPath("$.code").value("PRODUCT_IMAGE_NOT_FOUND"));
		this.mvc.perform(get(IMAGE, "pas-un-uuid").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.accept(MediaType.IMAGE_JPEG))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
		byte[] jpeg = jpeg(Color.PINK);
		UUID product = productWithImage(this.companyA, jpeg);
		this.mvc.perform(get(IMAGE, product).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.accept(MediaType.IMAGE_JPEG, MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(status().isOk()).andExpect(content().bytes(jpeg));
	}

	// --- Statuts de la liaison et portée ---------------------------------------------------------------------------

	@Test
	void aRevokedLinkIsUnauthorizedASuspendedOrUnscopedLinkIsForbidden() throws Exception {
		UUID product = productWithImage(this.companyA, jpeg(Color.GRAY));

		Link suspended = this.links.active(this.links.client(), this.companyA.companyId(), "shop-g19-" + UUID.randomUUID(), CATALOG);
		String suspendedToken = tokenOf(suspended, CATALOG);
		this.registry.suspend(suspended.id(), SuspensionReason.MANUAL);
		image(suspendedToken, product).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("LINK_SUSPENDED"));

		Link revoked = this.links.active(this.links.client(), this.companyA.companyId(), "shop-g19-" + UUID.randomUUID(), CATALOG);
		String revokedToken = tokenOf(revoked, CATALOG);
		this.registry.revoke(revoked.id(), RevocationReason.SHOP_REQUEST);
		image(revokedToken, product).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("LINK_REVOKED"));

		Link unscoped = this.links.active(this.links.client(), this.companyA.companyId(), "shop-g19-" + UUID.randomUUID(),
				Set.of("stock:read"));
		image(tokenOf(unscoped, Set.of("stock:read")), product).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("INSUFFICIENT_SCOPE"));

		this.mvc.perform(get(IMAGE, product)).andExpect(status().isUnauthorized());
	}

	// --- Débit (G19-D6) ---------------------------------------------------------------------------------------------

	@Test
	void theHundredTwentyFirstCallIsRefusedNotModifiedIncludedWithItsOwnCounter() throws Exception {
		Link link = this.links.active(this.links.client(), this.companyA.companyId(), "shop-g19-" + UUID.randomUUID(), CATALOG);
		String token = tokenOf(link, CATALOG);
		String other = token(this.companyA);
		byte[] jpeg = jpeg(Color.WHITE);
		UUID product = productWithImage(this.companyA, jpeg);
		String etag = "\"" + sha256(jpeg) + "\"";
		awaitFreshWindow();

		for (int i = 0; i < 60; i++) {
			image(token, product).andExpect(status().isOk());
		}
		for (int i = 0; i < 60; i++) {
			image(token, product, etag).andExpect(status().isNotModified());
		}
		image(token, product)
			.andExpect(status().isTooManyRequests())
			.andExpect(header().exists(HttpHeaders.RETRY_AFTER))
			.andExpect(jsonPath("$.code").value("RATE_LIMITED"));
		// Compteur propre à l'image : l'instantané de la même liaison n'est pas touché.
		this.mvc.perform(get(SNAPSHOT).param("limit", "1").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk());
		image(other, product).andExpect(status().isOk());
	}

	// --- HEAD (G19-D8) ----------------------------------------------------------------------------------------------

	@Test
	void aHeadRequestGivesTheHeadersWithoutBodyAndCountsAgainstTheImageLimit() throws Exception {
		Link link = this.links.active(this.links.client(), this.companyA.companyId(), "shop-g19-" + UUID.randomUUID(), CATALOG);
		String token = tokenOf(link, CATALOG);
		byte[] jpeg = jpeg(Color.DARK_GRAY);
		UUID product = productWithImage(this.companyA, jpeg);
		awaitFreshWindow();

		// 119 GET, puis HEAD en 120ᵉ appel : servi, en-têtes du GET, sans corps.
		for (int i = 0; i < 119; i++) {
			image(token, product).andExpect(status().isOk());
		}
		this.mvc.perform(head(IMAGE, product).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_JPEG_VALUE))
			.andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, jpeg.length))
			.andExpect(header().string(HttpHeaders.ETAG, "\"" + sha256(jpeg) + "\""))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
			.andExpect(content().bytes(new byte[0]));
		// Le HEAD a consommé une unité : le 121ᵉ appel, HEAD ou GET, est refusé.
		this.mvc.perform(head(IMAGE, product).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isTooManyRequests())
			.andExpect(header().exists(HttpHeaders.RETRY_AFTER))
			.andExpect(content().bytes(new byte[0]));
		image(token, product)
			.andExpect(status().isTooManyRequests())
			.andExpect(header().exists(HttpHeaders.RETRY_AFTER))
			.andExpect(jsonPath("$.code").value("RATE_LIMITED"));
	}

	// --- Outils ------------------------------------------------------------------------------------------------

	private ResultActions image(String token, UUID product) throws Exception {
		return this.mvc.perform(get(IMAGE, product).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
	}

	private ResultActions image(String token, UUID product, String ifNoneMatch) throws Exception {
		MockHttpServletRequestBuilder request = get(IMAGE, product).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.header(HttpHeaders.IF_NONE_MATCH, ifNoneMatch);
		return this.mvc.perform(request);
	}

	/** Réponse 404 : statut, format et code vérifiés ; renvoie code et message, pour comparer les cas entre eux. */
	private String notFound(String token, UUID product) throws Exception {
		String body = image(token, product).andExpect(status().isNotFound())
			.andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE))
			.andExpect(jsonPath("$.code").value("PRODUCT_IMAGE_NOT_FOUND"))
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		var node = this.json.readTree(body);
		return node.get("code").asString() + " | " + node.get("message").asString();
	}

	private String token(TenantRef company) {
		Link link = this.links.active(this.links.client(), company.companyId(), "shop-g19-" + UUID.randomUUID(), CATALOG);
		return tokenOf(link, CATALOG);
	}

	private String tokenOf(Link link, Set<String> scopes) {
		String clientId = this.platform.jdbc().sql("select client_id from platform.integration_client where id = :id")
			.param("id", link.clientId()).query(String.class).single();
		return remember(this.tokens.issue(link.id(), clientId, scopes).value());
	}

	private UUID product(TenantRef company) {
		UUID product = TenantContext.callAs(company, () -> {
			UUID category = this.jdbc.queryForObject("insert into category (name) values (?) returning id", UUID.class,
					"Catégorie G19 " + UUID.randomUUID());
			return this.jdbc.queryForObject("""
					insert into product (sku, name, category_id, purchase_price, retail_price, wholesale_price)
					values (?, 'Produit G19', ?, 1000, 2000, 1800) returning id""", UUID.class,
					"G19-" + UUID.randomUUID().toString().substring(0, 8), category);
		});
		if (company.equals(this.companyA)) {
			this.productsOfA.add(product);
		}
		return product;
	}

	/** Produit avec une image JPEG enregistrée telle quelle (le ré-encodage à l'envoi est couvert par le module produit). */
	private UUID productWithImage(TenantRef company, byte[] jpeg) {
		UUID product = product(company);
		TenantContext.runAs(company, () -> this.jdbc.update("""
				insert into product_image (product_id, content_type, data, size_bytes, width, height, sha256)
				values (?, 'image/jpeg', ?, ?, 8, 8, ?)""", product, jpeg, jpeg.length, sha256(jpeg)));
		return product;
	}

	private static byte[] jpeg(Color color) throws Exception {
		BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
		for (int x = 0; x < 8; x++) {
			for (int y = 0; y < 8; y++) {
				image.setRGB(x, y, color.getRGB());
			}
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "jpg", out);
		return out.toByteArray();
	}

	private static String sha256(byte[] data) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
		}
		catch (java.security.NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
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
		String code = "it-g19-" + UUID.randomUUID().toString().substring(0, 8);
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
