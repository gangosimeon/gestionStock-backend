package com.geststock.integration.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.geststock.platform.PlatformDb;
import com.geststock.support.PostgresTestConfiguration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Rotation du secret de liaison (lot 2, étape G14 ; v14-D6, contrat {@code POST /link/secret},
 * {@code LinkSecretResponse}) : nouveau secret remis une seule fois, empreinte seule en base ; ancien et nouveau
 * acceptés pendant la transition ; promotion au premier usage du nouveau (mise à jour conditionnelle unique) ou au-delà
 * de 24 h ; 5 rotations par heure et par liaison ; audit ; aucun secret ni empreinte dans les journaux, tous en TRACE.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class IntegrationLinkSecretIT {

	private static final String TOKEN = "/api/integration/v1/oauth/token";

	private static final String SECRET = "/api/integration/v1/link/secret";

	private static final Set<String> ALLOWED = Set.of("catalog:read", "orders:read");

	private static final SecureRandom RANDOM = new SecureRandom();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private PlatformDb platform;

	@Autowired
	private LinkRegistry registry;

	@Autowired
	private JsonMapper json;

	private TestLinks links;

	private final List<String> sensitive = new ArrayList<>();

	private String clientSecret;

	private TestLinks.Client client;

	private ListAppender<ILoggingEvent> logs;

	/** Niveaux d'origine des journaux passés en TRACE (null : hérité). */
	private final Map<Logger, Level> levels = new LinkedHashMap<>();

	@BeforeEach
	void setUp() {
		this.links = new TestLinks(this.platform, this.registry);
		this.clientSecret = remember(randomToken());
		this.client = this.links.client(this.clientSecret, null, ALLOWED);
		// Tout en TRACE, y compris les journaux qui ont un niveau propre (profil de test, Spring, Hikari…).
		LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
		for (Logger logger : context.getLoggerList()) {
			// Sauf le pilote PostgreSQL, que la configuration fixe à INFO (comme IntegrationAuditIT, G12) : sa trace
			// protocolaire afficherait chaque paramètre lié, et un niveau général TRACE ne l'active jamais.
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
		((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
			.detachAppender(this.logs);
		this.levels.forEach(Logger::setLevel);
		List<String> messages = this.logs.list.stream().map(IntegrationLinkSecretIT::render).toList();
		try {
			assertThat(this.logs.list).as("journaux réellement capturés en TRACE")
				.anyMatch(event -> event.getLevel() == Level.TRACE);
			assertThat(messages).as("aucun secret, empreinte, jeton ni en-tête Basic dans les journaux")
				.allSatisfy(message -> this.sensitive.forEach(value -> assertThat(message).doesNotContain(value)));
		}
		finally {
			this.links.cleanUp();
		}
	}

	// --- Rotation -------------------------------------------------------------------------------------------

	@Test
	void aRotationHandsTheNewSecretOnceStoresOnlyItsHashAndIsNeverCached() throws Exception {
		ActivatedLink link = activeLink("shop-rotation");
		String token = token(link.link().id(), link.secret());

		MockHttpServletResponse response = rotate(token).andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
			.andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
			.andReturn().getResponse();
		JsonNode body = read(response.getContentAsString());
		assertThat(body.propertyNames()).containsExactlyInAnyOrder("link_secret", "rotatedAt", "previousSecretValidUntil");
		String newSecret = remember(body.get("link_secret").asString());
		remember(sha256Hex(newSecret));
		assertThat(newSecret).as("256 bits, base64url sans remplissage").matches("^[A-Za-z0-9_-]{43}$")
			.isNotEqualTo(link.secret());
		Instant rotatedAt = Instant.parse(body.get("rotatedAt").asString());
		Instant validUntil = Instant.parse(body.get("previousSecretValidUntil").asString());
		assertThat(Duration.between(rotatedAt, validUntil)).isEqualTo(Duration.ofHours(24));

		Map<String, Object> row = row(link.link().id());
		assertThat(row.get("secret_hash")).as("secret courant inchangé").isEqualTo(sha256Hex(link.secret()));
		assertThat(row.get("pending_secret_hash")).as("empreinte seule").isEqualTo(sha256Hex(newSecret));
		assertThat(row.get("pending_secret_created_at")).isNotNull();
		assertThat(rowText(link.link().id())).as("aucun secret en clair en base").doesNotContain(newSecret)
			.doesNotContain(link.secret());
	}

	@Test
	void duringTheTransitionTheOldAndTheNewSecretBothGetAToken() throws Exception {
		ActivatedLink link = activeLink("shop-transition");
		String newSecret = newSecret(rotate(token(link.link().id(), link.secret())));

		credentials(link.link().id(), link.secret()).andExpect(status().isOk());
		credentials(link.link().id(), link.secret()).andExpect(status().isOk());
		assertThat(row(link.link().id()).get("pending_secret_hash")).as("l'ancien ne promeut rien")
			.isEqualTo(sha256Hex(newSecret));
		credentials(link.link().id(), newSecret).andExpect(status().isOk());
	}

	@Test
	void theFirstUseOfTheNewSecretPromotesItAndTheOldOneIsThenRefused() throws Exception {
		ActivatedLink link = activeLink("shop-promotion");
		String newSecret = newSecret(rotate(token(link.link().id(), link.secret())));

		credentials(link.link().id(), newSecret).andExpect(status().isOk());
		Map<String, Object> row = row(link.link().id());
		assertThat(row.get("secret_hash")).as("nouveau secret courant").isEqualTo(sha256Hex(newSecret));
		assertThat(row.get("pending_secret_hash")).isNull();
		assertThat(row.get("pending_secret_created_at")).isNull();

		credentials(link.link().id(), link.secret()).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("invalid_grant"));
		credentials(link.link().id(), newSecret).andExpect(status().isOk());
		assertThat(audit(link.link().id(), LinkAudit.SECRET_PROMOTED)).containsExactly("CLIENT FIRST_USE");
	}

	@Test
	void twoSimultaneousExchangesWithTheNewSecretPromoteItOnceAndBothGetAToken() throws Exception {
		ActivatedLink link = activeLink("shop-promotion-race");
		String newSecret = newSecret(rotate(token(link.link().id(), link.secret())));
		CyclicBarrier barrier = new CyclicBarrier(2);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				futures.add(executor.submit(() -> {
					barrier.await(10, TimeUnit.SECONDS);
					return this.mvc.perform(asClient(form(TOKEN, grant(link.link().id(), newSecret)))).andReturn().getResponse();
				}));
			}
			for (Future<MockHttpServletResponse> future : futures) {
				MockHttpServletResponse response = future.get(30, TimeUnit.SECONDS);
				assertThat(response.getStatus()).as("les deux reçoivent un jeton").isEqualTo(200);
				remember(read(response.getContentAsString()).get("access_token").asString());
			}
		}
		finally {
			executor.shutdownNow();
		}
		assertThat(audit(link.link().id(), LinkAudit.SECRET_PROMOTED)).as("une seule promotion").hasSize(1);
		assertThat(row(link.link().id()).get("secret_hash")).isEqualTo(sha256Hex(newSecret));
	}

	@Test
	void aSecondRotationBeforeUseReplacesThePendingSecret() throws Exception {
		ActivatedLink link = activeLink("shop-rotation-twice");
		String token = token(link.link().id(), link.secret());
		String first = newSecret(rotate(token));
		String second = newSecret(rotate(token));
		assertThat(second).isNotEqualTo(first);
		assertThat(row(link.link().id()).get("pending_secret_hash")).isEqualTo(sha256Hex(second));

		credentials(link.link().id(), first).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("invalid_grant"));
		credentials(link.link().id(), link.secret()).andExpect(status().isOk());
		credentials(link.link().id(), second).andExpect(status().isOk());
		assertThat(row(link.link().id()).get("secret_hash")).isEqualTo(sha256Hex(second));
	}

	@Test
	void beyondTwentyFourHoursTheOldSecretIsRefusedAndTheNewOneIsAcceptedAndPromoted() throws Exception {
		// Ancien secret présenté d'abord : refusé, et le secret en attente promu à cette vérification.
		ActivatedLink link = activeLink("shop-24h-old");
		String newSecret = newSecret(rotate(token(link.link().id(), link.secret())));
		age(link.link().id());
		credentials(link.link().id(), link.secret()).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("invalid_grant"));
		Map<String, Object> row = row(link.link().id());
		assertThat(row.get("secret_hash")).as("promu à la première vérification").isEqualTo(sha256Hex(newSecret));
		assertThat(row.get("pending_secret_hash")).isNull();
		credentials(link.link().id(), newSecret).andExpect(status().isOk());
		assertThat(audit(link.link().id(), LinkAudit.SECRET_PROMOTED)).containsExactly("SYSTEM PREVIOUS_SECRET_EXPIRED");

		// Nouveau secret présenté d'abord : accepté et promu ; l'ancien ensuite refusé.
		ActivatedLink other = activeLink("shop-24h-new");
		String otherSecret = newSecret(rotate(token(other.link().id(), other.secret())));
		age(other.link().id());
		credentials(other.link().id(), otherSecret).andExpect(status().isOk());
		assertThat(row(other.link().id()).get("secret_hash")).isEqualTo(sha256Hex(otherSecret));
		credentials(other.link().id(), other.secret()).andExpect(status().isBadRequest());
		assertThat(audit(other.link().id(), LinkAudit.SECRET_PROMOTED)).containsExactly("SYSTEM PREVIOUS_SECRET_EXPIRED");
	}

	@Test
	void aSuspendedLinkGetsLinkSuspendedAndARevokedLinkLinkRevokedWithBothHashesCleared() throws Exception {
		ActivatedLink link = activeLink("shop-rotation-suspended");
		String token = token(link.link().id(), link.secret());
		String pending = newSecret(rotate(token));
		this.registry.suspend(link.link().id(), SuspensionReason.MANUAL);
		rotate(token).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("LINK_SUSPENDED"));
		assertThat(row(link.link().id()).get("pending_secret_hash")).as("rien de changé").isEqualTo(sha256Hex(pending));

		this.registry.revoke(link.link().id(), RevocationReason.SHOP_REQUEST);
		rotate(token).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("LINK_REVOKED"));
		Map<String, Object> row = row(link.link().id());
		assertThat(row.get("secret_hash")).isNull();
		assertThat(row.get("pending_secret_hash")).isNull();
		assertThat(row.get("pending_secret_created_at")).isNull();
		assertThat(this.registry.verifySecret(link.link().id(), link.secret())).isFalse();
		assertThat(this.registry.verifySecret(link.link().id(), pending)).isFalse();
	}

	@Test
	void theSixthRotationWithinTheHourIsRateLimitedWithRetryAfter() throws Exception {
		ActivatedLink link = activeLink("shop-rotation-limit");
		String token = token(link.link().id(), link.secret());
		for (int i = 0; i < 5; i++) {
			newSecret(rotate(token));
		}
		MockHttpServletResponse refused = rotate(token).andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("RATE_LIMITED"))
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
			.andReturn().getResponse();
		assertThat(Integer.parseInt(refused.getHeader(HttpHeaders.RETRY_AFTER))).isBetween(1, 3600);
	}

	@Test
	void eachRotationAndEachPromotionIsAuditedWithoutAnySensitiveValue() throws Exception {
		ActivatedLink link = activeLink("shop-rotation-audit");
		String token = token(link.link().id(), link.secret());
		newSecret(rotate(token));
		String second = newSecret(rotate(token));
		credentials(link.link().id(), second).andExpect(status().isOk());

		assertThat(audit(link.link().id(), LinkAudit.SECRET_ROTATED)).as("une trace par rotation, par la boutique")
			.containsExactly("SHOP null", "SHOP null");
		assertThat(audit(link.link().id(), LinkAudit.SECRET_PROMOTED)).containsExactly("CLIENT FIRST_USE");
		List<String> rows = this.platform.jdbc()
			.sql("select row_to_json(a)::text from platform.integration_audit_log a where link_id = :id")
			.param("id", link.link().id()).query(String.class).list();
		assertThat(rows).isNotEmpty()
			.allSatisfy(text -> this.sensitive.forEach(value -> assertThat(text).doesNotContain(value)));
	}

	// --- Outils ---------------------------------------------------------------------------------------------

	private ActivatedLink activeLink(String shop) {
		ActivatedLink activated = this.registry.activate(this.links.pending(this.client, this.links.legacyCompany(), shop,
				Set.of("catalog:read")).id());
		remember(activated.secret());
		remember(sha256Hex(activated.secret()));
		return activated;
	}

	private String token(UUID linkId, String secret) throws Exception {
		JsonNode body = read(credentials(linkId, secret).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		return remember(body.get("access_token").asString());
	}

	private ResultActions credentials(UUID linkId, String secret) throws Exception {
		ResultActions result = this.mvc.perform(asClient(form(TOKEN, grant(linkId, secret))));
		String content = result.andReturn().getResponse().getContentAsString();
		if (content.contains("access_token")) {
			remember(read(content).get("access_token").asString());
		}
		return result;
	}

	private ResultActions rotate(String token) throws Exception {
		return this.mvc.perform(post(SECRET).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
	}

	private String newSecret(ResultActions rotation) throws Exception {
		JsonNode body = read(rotation.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		String secret = remember(body.get("link_secret").asString());
		remember(sha256Hex(secret));
		return secret;
	}

	/** Secret en attente émis il y a plus de 24 h (horloge de la base). */
	private void age(UUID linkId) {
		this.platform.jdbc().sql("""
				update platform.integration_link
				set pending_secret_created_at = now() - interval '24 hours 1 minute' where id = :id""").param("id", linkId).update();
	}

	private Map<String, Object> row(UUID linkId) {
		return this.platform.jdbc()
			.sql("select secret_hash, pending_secret_hash, pending_secret_created_at from platform.integration_link where id = :id")
			.param("id", linkId).query().singleRow();
	}

	private String rowText(UUID linkId) {
		return this.platform.jdbc().sql("select row_to_json(l)::text from platform.integration_link l where id = :id")
			.param("id", linkId).query(String.class).single();
	}

	/** Traces de l'action pour la liaison : « type d'auteur cause ». */
	private List<String> audit(UUID linkId, String action) {
		return this.platform.jdbc().sql("""
				select actor_type || ' ' || coalesce(details ->> 'cause', 'null') from platform.integration_audit_log
				where link_id = :id and action = :action order by occurred_at, id""")
			.param("id", linkId).param("action", action).query(String.class).list();
	}

	private static Map<String, String> grant(UUID linkId, String secret) {
		Map<String, String> params = new LinkedHashMap<>();
		params.put("grant_type", "client_credentials");
		params.put("link_id", linkId.toString());
		params.put("link_secret", secret);
		return params;
	}

	private static MockHttpServletRequestBuilder form(String path, Map<String, String> params) {
		MockHttpServletRequestBuilder request = post(path).contentType(MediaType.APPLICATION_FORM_URLENCODED);
		params.forEach(request::param);
		return request;
	}

	private MockHttpServletRequestBuilder asClient(MockHttpServletRequestBuilder request) {
		return request.header(HttpHeaders.AUTHORIZATION, "Basic " + remember(Base64.getEncoder()
			.encodeToString((this.client.clientId() + ":" + this.clientSecret).getBytes(StandardCharsets.UTF_8))));
	}

	private JsonNode read(String body) {
		return this.json.readTree(body);
	}

	private synchronized String remember(String value) {
		this.sensitive.add(value);
		return value;
	}

	/** Message, arguments bruts et exceptions (causes comprises) d'un événement. */
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

	private static String randomToken() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String sha256Hex(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (java.security.NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
