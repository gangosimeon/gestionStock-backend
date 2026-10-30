package com.geststock.integration.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
 * Routes du client JeCréeMaBoutique (lot 2, étape G8 ; v14-D1, D3, D12, D14, contrat) : {@code POST /oauth/par} et
 * {@code POST /oauth/token} (grants {@code authorization_code} avec PKCE S256 et {@code client_credentials}),
 * authentifiées en HTTP Basic par le client, corps {@code application/x-www-form-urlencoded}, erreurs au format OAuth,
 * {@code Cache-Control: no-store}. Aucun secret, code, vérificateur ni {@code request_uri} dans les journaux.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({ PostgresTestConfiguration.class, IntegrationOAuthIT.OAuthTestController.class })
class IntegrationOAuthIT {

	private static final String PAR = "/api/integration/v1/oauth/par";

	private static final String TOKEN = "/api/integration/v1/oauth/token";

	private static final String CATALOG = "/api/integration/v1/test-oauth/catalog";

	private static final String DURING_SUSPENSION = "/api/integration/v1/test-oauth/during-suspension";

	private static final Set<String> ALLOWED = Set.of("catalog:read", "orders:read", "orders:write");

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

	private String secret;

	private TestLinks.Client client;

	private ListAppender<ILoggingEvent> logs;

	/** Niveaux d'origine des journaux passés en TRACE (null : hérité). */
	private final Map<Logger, Level> levels = new LinkedHashMap<>();

	@BeforeEach
	void setUp() {
		this.links = new TestLinks(this.platform, this.registry);
		this.secret = remember(randomToken());
		this.client = this.links.client(this.secret, null, ALLOWED);
		// Tout en TRACE, y compris les journaux qui ont un niveau propre (profil de test, Spring, Hikari…).
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
		((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
			.detachAppender(this.logs);
		this.levels.forEach(Logger::setLevel);
		List<String> messages = this.logs.list.stream().map(IntegrationOAuthIT::render).toList();
		try {
			assertThat(this.logs.list).as("journaux réellement capturés en TRACE")
				.anyMatch(event -> event.getLevel() == Level.TRACE);
			// Toute requête arrivée jusqu'à Spring MVC est journalisée en TRACE, paramètres et en-têtes masqués.
			assertThat(this.logs.list.stream()
				.filter(event -> event.getLoggerName().equals("org.springframework.web.servlet.DispatcherServlet"))
				.map(ILoggingEvent::getFormattedMessage)
				.filter(message -> message.startsWith("POST \"/api/integration/v1/oauth/")))
				.as("requêtes journalisées, paramètres et en-têtes masqués")
				.allMatch(message -> message.contains("parameters={masked}, headers={masked}"));
			assertThat(messages).as("aucun secret, en-tête Basic, code, vérificateur ni request_uri dans les journaux")
				.allSatisfy(message -> this.sensitive.forEach(value -> assertThat(message).doesNotContain(value)));
		}
		finally {
			this.links.cleanUp();
		}
	}

	// --- Authentification du client ---------------------------------------------------------------------

	@Test
	void anUnknownClientAWrongSecretOrADisabledClientGetTheSameInvalidClient() throws Exception {
		Map<String, String> params = parParams("shop-basic", verifier());
		TestLinks.Client disabled = this.links.client(remember(randomToken()), null, ALLOWED);
		this.platform.jdbc().sql("update platform.integration_client set status = 'DISABLED', disabled_at = now() where id = :id")
			.param("id", disabled.id()).update();

		List<MockHttpServletResponse> responses = List.of(
				invalidClient(form(PAR, params).header(HttpHeaders.AUTHORIZATION, basic("jcmb-inconnu", this.secret))),
				invalidClient(form(PAR, params).header(HttpHeaders.AUTHORIZATION, basic(this.client.clientId(), "mauvais-secret"))),
				invalidClient(form(PAR, params).header(HttpHeaders.AUTHORIZATION, basic(disabled.clientId(), this.secret))),
				invalidClient(form(PAR, params)),
				invalidClient(form(TOKEN, Map.of("grant_type", "client_credentials")).header(HttpHeaders.AUTHORIZATION,
						basic(this.client.clientId(), "mauvais-secret"))));

		assertThat(responses).extracting(MockHttpServletResponse::getContentAsString).as("corps identiques")
			.containsOnly(responses.get(0).getContentAsString());
	}

	@Test
	void theSecondarySecretOfTheClientIsAccepted() throws Exception {
		String secondary = remember(randomToken());
		TestLinks.Client rotating = this.links.client(remember(randomToken()), secondary, ALLOWED);

		this.mvc.perform(form(PAR, parParams("shop-rotation", verifier())).header(HttpHeaders.AUTHORIZATION,
				basic(rotating.clientId(), secondary)))
			.andExpect(status().isCreated());
	}

	// --- PAR ------------------------------------------------------------------------------------------------

	@Test
	void aPushedAuthorizationRequestIsStoredByItsHashOnlyAndExpiresInTenMinutes() throws Exception {
		JsonNode body = read(noStore(this.mvc.perform(asClient(form(PAR, parParams("shop-par", verifier())))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.expires_in").value(600)));
		String requestUri = remember(body.get("request_uri").asString());

		assertThat(requestUri).matches("^urn:ietf:params:oauth:request_uri:[A-Za-z0-9_-]{43}$");
		Map<String, Object> row = this.platform.jdbc().sql("""
				select *, extract(epoch from expires_at - created_at) as lifetime
				from platform.integration_authorization_request where request_uri_hash = :hash""")
			.param("hash", sha256Hex(requestUri)).query().singleRow();
		assertThat(row.get("client_id")).isEqualTo(this.client.id());
		assertThat(row.get("external_shop_id")).isEqualTo("shop-par");
		assertThat(((Number) row.get("lifetime")).intValue()).isEqualTo(600);
		assertThat(row.get("decision")).isNull();
		assertThat(row.values()).as("request_uri jamais stocké en clair")
			.noneMatch(value -> value != null && value.toString().contains(requestUri.substring(requestUri.lastIndexOf(':') + 1)));
	}

	@Test
	void invalidPushedAuthorizationRequestsAreRefused() throws Exception {
		String verifier = verifier();
		parError(with(parParams("shop-x", verifier), "redirect_uri", "https://www.jecreemaboutique.com/autre"), "invalid_request");
		parError(with(parParams("shop-x", verifier), "redirect_uri", TestLinks.REDIRECT_URI + "/"), "invalid_request");
		parError(with(parParams("shop-x", verifier), "redirect_uri", TestLinks.REDIRECT_URI.replace("callback", "callbacK")),
				"invalid_request");
		parError(with(parParams("shop-x", verifier), "code_challenge_method", "plain"), "invalid_request");
		parError(with(parParams("shop-x", verifier), "code_challenge", "trop-court"), "invalid_request");
		parError(with(parParams("shop-x", verifier), "response_type", "token"), "invalid_request");
		parError(with(parParams("shop-x", verifier), "scope", "catalog:read price:propose"), "invalid_scope");
		parError(with(parParams("shop-x", verifier), "scope", ""), "invalid_scope");
		parError(with(parParams("shop-x", verifier), "state", "s".repeat(31)), "invalid_request");
		parError(with(parParams("shop-x", verifier), "state", "s".repeat(513)), "invalid_request");
		parError(with(parParams("shop-x", verifier), "shop_id", " "), "invalid_request");
		parError(with(parParams("shop-x", verifier), "shop_name", ""), "invalid_request");
		Map<String, String> missing = parParams("shop-x", verifier);
		missing.remove("code_challenge");
		parError(missing, "invalid_request");
		// Corps JSON au lieu d'un formulaire.
		this.mvc.perform(asClient(post(PAR).contentType(MediaType.APPLICATION_JSON).content("{}")))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"));
		assertThat(requestsOf(this.client)).isZero();
	}

	@Test
	void aShopAlreadyLinkedIsRefusedWithoutRevealingTheCompany() throws Exception {
		this.links.active(this.client, this.links.legacyCompany(), "shop-linked", Set.of("catalog:read"));

		String body = parError(parParams("shop-linked", verifier()), "invalid_request").andReturn().getResponse().getContentAsString();

		assertThat(body).doesNotContain("test").doesNotContain(this.links.legacyCompany().toString());
	}

	// --- authorization_code ----------------------------------------------------------------------------

	@Test
	void theWholeAuthorizationCodeFlowActivatesTheLinkAndHandsItsSecretOnce() throws Exception {
		String verifier = verifier();
		Approved approved = approve(par("shop-flow", verifier), Set.of("catalog:read", "orders:read"));

		JsonNode body = read(noStore(this.mvc.perform(asClient(form(TOKEN, codeGrant(approved.code(), verifier)))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.token_type").value("Bearer"))
			.andExpect(jsonPath("$.expires_in").value(900))
			.andExpect(jsonPath("$.scope").value("catalog:read orders:read"))
			.andExpect(jsonPath("$.link.id").value(approved.link().toString()))
			.andExpect(jsonPath("$.link.status").value("ACTIVE"))
			.andExpect(jsonPath("$.link.companyCode").value("test"))
			.andExpect(jsonPath("$.link.externalShopId").value("shop-flow"))
			.andExpect(jsonPath("$.link.currency").value("XOF")));
		String linkSecret = remember(body.get("link_secret").asString());
		String accessToken = remember(body.get("access_token").asString());

		assertThat(linkSecret).matches("^[A-Za-z0-9_-]{43}$");
		assertThat(this.registry.verifySecret(approved.link(), linkSecret)).isTrue();
		assertThat(this.registry.find(approved.link()).orElseThrow().status()).isEqualTo(LinkStatus.ACTIVE);
		bearer(get(CATALOG), accessToken).andExpect(status().isOk());
	}

	@Test
	void anExpiredForeignMismatchedOrUnknownCodeIsAnInvalidGrantAndActivatesNothing() throws Exception {
		String verifier = verifier();
		Approved approved = approve(par("shop-refused", verifier), Set.of("catalog:read"));

		tokenError(codeGrant(approved.code(), verifier()), "invalid_grant");
		tokenError(with(codeGrant(approved.code(), verifier), "redirect_uri", TestLinks.REDIRECT_URI + "x"), "invalid_grant");
		String otherSecret = remember(randomToken());
		TestLinks.Client other = this.links.client(otherSecret, null, ALLOWED);
		this.mvc.perform(form(TOKEN, codeGrant(approved.code(), verifier)).header(HttpHeaders.AUTHORIZATION,
				basic(other.clientId(), otherSecret)))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));
		tokenError(codeGrant(remember(randomToken()), verifier), "invalid_grant");
		assertThat(this.registry.find(approved.link()).orElseThrow().status()).as("aucune liaison activée")
			.isEqualTo(LinkStatus.PENDING);

		// Le code n'a pas été consommé par ces refus : l'échange correct réussit encore.
		this.mvc.perform(asClient(form(TOKEN, codeGrant(approved.code(), verifier)))).andExpect(status().isOk());

		Approved expired = approve(par("shop-expired", verifier), Set.of("catalog:read"));
		this.platform.jdbc().sql("""
				update platform.integration_authorization_code
				set created_at = now() - interval '10 minutes', expires_at = now() - interval '5 minutes' where link_id = :link""")
			.param("link", expired.link()).update();
		tokenError(codeGrant(expired.code(), verifier), "invalid_grant");
		assertThat(this.registry.find(expired.link()).orElseThrow().status()).isEqualTo(LinkStatus.PENDING);
	}

	@Test
	void aReplayedCodeIsRefusedAndRevokesTheLinkItCreated() throws Exception {
		String verifier = verifier();
		Approved approved = approve(par("shop-replay", verifier), Set.of("catalog:read"));
		JsonNode first = read(this.mvc.perform(asClient(form(TOKEN, codeGrant(approved.code(), verifier)))).andExpect(status().isOk()));
		String linkSecret = remember(first.get("link_secret").asString());
		remember(first.get("access_token").asString());

		tokenError(codeGrant(approved.code(), verifier), "invalid_grant");

		Link link = this.registry.find(approved.link()).orElseThrow();
		assertThat(link.status()).isEqualTo(LinkStatus.REVOKED);
		assertThat(link.revocationReason()).isEqualTo(RevocationReason.CODE_REPLAYED);
		assertThat(this.registry.verifySecret(approved.link(), linkSecret)).isFalse();
	}

	@Test
	void twoSimultaneousExchangesOfTheSameCodeSucceedOnceAndTheOtherIsAReplay() throws Exception {
		String verifier = verifier();
		Approved approved = approve(par("shop-race", verifier), Set.of("catalog:read"));
		CyclicBarrier barrier = new CyclicBarrier(2);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				futures.add(executor.submit(() -> {
					barrier.await(10, TimeUnit.SECONDS);
					return this.mvc.perform(asClient(form(TOKEN, codeGrant(approved.code(), verifier)))).andReturn().getResponse();
				}));
			}
			List<Integer> statuses = new ArrayList<>();
			for (Future<MockHttpServletResponse> future : futures) {
				MockHttpServletResponse response = future.get(30, TimeUnit.SECONDS);
				statuses.add(response.getStatus());
				if (response.getStatus() == 200) {
					JsonNode body = read(response.getContentAsString());
					remember(body.get("link_secret").asString());
					remember(body.get("access_token").asString());
				}
				else {
					assertThat(read(response.getContentAsString()).get("error").asString()).isEqualTo("invalid_grant");
				}
			}
			assertThat(statuses).containsExactlyInAnyOrder(200, 400);
		}
		finally {
			executor.shutdownNow();
		}
		Link link = this.registry.find(approved.link()).orElseThrow();
		assertThat(link.status()).as("le second échange est un rejeu").isEqualTo(LinkStatus.REVOKED);
		assertThat(link.revocationReason()).isEqualTo(RevocationReason.CODE_REPLAYED);
	}

	// --- client_credentials --------------------------------------------------------------------------------

	@Test
	void clientCredentialsGiveATokenWithTheScopesOfTheLink() throws Exception {
		ActivatedLink link = activeLink("shop-cc", Set.of("catalog:read", "orders:read"));

		JsonNode body = read(noStore(this.mvc.perform(asClient(form(TOKEN, credentialsGrant(link)))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.token_type").value("Bearer"))
			.andExpect(jsonPath("$.expires_in").value(900))
			.andExpect(jsonPath("$.scope").value("catalog:read orders:read"))
			.andExpect(jsonPath("$.link").doesNotExist())
			.andExpect(jsonPath("$.link_secret").doesNotExist()));
		bearer(get(CATALOG), remember(body.get("access_token").asString())).andExpect(status().isOk());

		this.mvc.perform(asClient(form(TOKEN, with(credentialsGrant(link), "scope", "catalog:read"))))
			.andExpect(status().isOk()).andExpect(jsonPath("$.scope").value("catalog:read"));
		tokenError(with(credentialsGrant(link), "scope", "orders:write"), "invalid_scope");
	}

	@Test
	void aWrongSecretAForeignOrUnknownLinkIsAnInvalidGrant() throws Exception {
		ActivatedLink link = activeLink("shop-cc-refused", Set.of("catalog:read"));

		tokenError(with(credentialsGrant(link), "link_secret", remember(randomToken())), "invalid_grant");
		tokenError(with(credentialsGrant(link), "link_id", UUID.randomUUID().toString()), "invalid_grant");
		tokenError(with(credentialsGrant(link), "link_id", "pas-un-uuid"), "invalid_request");
		String otherSecret = remember(randomToken());
		TestLinks.Client other = this.links.client(otherSecret, null, ALLOWED);
		this.mvc.perform(form(TOKEN, credentialsGrant(link)).header(HttpHeaders.AUTHORIZATION, basic(other.clientId(), otherSecret)))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));
		tokenError(Map.of("grant_type", "password"), "unsupported_grant_type");
		tokenError(Map.of(), "invalid_request");
	}

	@Test
	void aRevokedLinkGetsLinkRevokedAndASuspendedLinkStillGetsAToken() throws Exception {
		ActivatedLink revoked = activeLink("shop-cc-revoked", Set.of("catalog:read"));
		this.registry.revoke(revoked.link().id(), RevocationReason.SHOP_REQUEST);
		// 400 et non 401 : au point d'émission des jetons, 401 est réservé à invalid_client (RFC 6749 § 5.2).
		noStore(this.mvc.perform(asClient(form(TOKEN, credentialsGrant(revoked)))))
			.andExpect(status().isBadRequest())
			.andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
			.andExpect(jsonPath("$.error").value("invalid_grant"))
			.andExpect(jsonPath("$.code").value("LINK_REVOKED"));

		ActivatedLink suspended = activeLink("shop-cc-suspended", Set.of("catalog:read"));
		this.registry.suspend(suspended.link().id(), SuspensionReason.MANUAL);
		JsonNode body = read(this.mvc.perform(asClient(form(TOKEN, credentialsGrant(suspended)))).andExpect(status().isOk()));
		String token = remember(body.get("access_token").asString());
		bearer(get(CATALOG), token).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("LINK_SUSPENDED"));
		bearer(get(DURING_SUSPENSION), token).andExpect(status().isOk());
	}

	// --- Outils ---------------------------------------------------------------------------------------------

	private record Approved(String code, UUID link) {
	}

	private String par(String shop, String verifier) throws Exception {
		JsonNode body = read(this.mvc.perform(asClient(form(PAR, parParams(shop, verifier)))).andExpect(status().isCreated()));
		return remember(body.get("request_uri").asString());
	}

	/** Approbation simulée (l'écran de consentement arrive à l'étape G10) : décision, liaison en attente, code. */
	private Approved approve(String requestUri, Set<String> scopes) {
		UUID request = this.platform.jdbc().sql("""
				update platform.integration_authorization_request set decision = 'APPROVED', used_at = now()
				where request_uri_hash = :hash returning id""").param("hash", sha256Hex(requestUri)).query(UUID.class).single();
		Link link = this.registry.createPending(request, this.links.legacyCompany(), new Authorizer(UUID.randomUUID(), "admin"),
				scopes);
		String code = remember(randomToken());
		this.platform.jdbc().sql("""
				insert into platform.integration_authorization_code (code_hash, request_id, link_id, expires_at)
				values (:hash, :request, :link, now() + interval '5 minutes')""")
			.param("hash", sha256Hex(code)).param("request", request).param("link", link.id()).update();
		return new Approved(code, link.id());
	}

	private ActivatedLink activeLink(String shop, Set<String> scopes) {
		ActivatedLink activated = this.registry.activate(this.links.pending(this.client, this.links.legacyCompany(), shop, scopes).id());
		remember(activated.secret());
		return activated;
	}

	private Map<String, String> parParams(String shop, String verifier) {
		Map<String, String> params = new LinkedHashMap<>();
		params.put("response_type", "code");
		params.put("redirect_uri", TestLinks.REDIRECT_URI);
		params.put("code_challenge", challenge(verifier));
		params.put("code_challenge_method", "S256");
		params.put("scope", "catalog:read orders:read");
		params.put("state", randomToken());
		params.put("shop_id", shop);
		params.put("shop_name", "Boutique " + shop);
		return params;
	}

	private static Map<String, String> codeGrant(String code, String verifier) {
		Map<String, String> params = new LinkedHashMap<>();
		params.put("grant_type", "authorization_code");
		params.put("code", code);
		params.put("redirect_uri", TestLinks.REDIRECT_URI);
		params.put("code_verifier", verifier);
		return params;
	}

	private static Map<String, String> credentialsGrant(ActivatedLink link) {
		Map<String, String> params = new LinkedHashMap<>();
		params.put("grant_type", "client_credentials");
		params.put("link_id", link.link().id().toString());
		params.put("link_secret", link.secret());
		return params;
	}

	private static Map<String, String> with(Map<String, String> params, String name, String value) {
		Map<String, String> copy = new LinkedHashMap<>(params);
		copy.put(name, value);
		return copy;
	}

	private static MockHttpServletRequestBuilder form(String path, Map<String, String> params) {
		MockHttpServletRequestBuilder request = post(path).contentType(MediaType.APPLICATION_FORM_URLENCODED);
		params.forEach(request::param);
		return request;
	}

	private MockHttpServletRequestBuilder asClient(MockHttpServletRequestBuilder request) {
		return request.header(HttpHeaders.AUTHORIZATION, basic(this.client.clientId(), this.secret));
	}

	private ResultActions bearer(MockHttpServletRequestBuilder request, String token) throws Exception {
		return this.mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
	}

	private ResultActions parError(Map<String, String> params, String error) throws Exception {
		return noStore(this.mvc.perform(asClient(form(PAR, params)))).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value(error));
	}

	private ResultActions tokenError(Map<String, String> params, String error) throws Exception {
		return noStore(this.mvc.perform(asClient(form(TOKEN, params)))).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value(error));
	}

	private MockHttpServletResponse invalidClient(MockHttpServletRequestBuilder request) throws Exception {
		return noStore(this.mvc.perform(request)).andExpect(status().isUnauthorized())
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Basic")))
			.andExpect(jsonPath("$.error").value("invalid_client"))
			.andReturn().getResponse();
	}

	private static ResultActions noStore(ResultActions actions) throws Exception {
		return actions.andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
			.andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"));
	}

	private int requestsOf(TestLinks.Client client) {
		return this.platform.jdbc().sql("select count(*) from platform.integration_authorization_request where client_id = :c")
			.param("c", client.id()).query(Integer.class).single();
	}

	private JsonNode read(ResultActions actions) throws Exception {
		return read(actions.andReturn().getResponse().getContentAsString());
	}

	private JsonNode read(String body) {
		return this.json.readTree(body);
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

	private static String challenge(String verifier) {
		try {
			return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
		}
		catch (java.security.NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String sha256Hex(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (java.security.NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** Routes d'intégration de test, chargées seulement dans ce contexte. */
	@RestController
	@RequestMapping("/api/integration/v1/test-oauth")
	static class OAuthTestController {

		@GetMapping("/catalog")
		@RequiresScope("catalog:read")
		Map<String, Object> catalog() {
			return Map.of("ok", true);
		}

		@GetMapping("/during-suspension")
		@RequiresScope(value = RequiresScope.ANY, allowedWhileSuspended = true)
		Map<String, Object> duringSuspension() {
			return Map.of("ok", true);
		}

	}

}
