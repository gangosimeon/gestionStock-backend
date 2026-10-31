package com.geststock.integration.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.geststock.platform.PlatformDb;
import com.geststock.security.JwtTokenService;
import com.geststock.support.PostgresTestConfiguration;
import com.geststock.tenancy.TenantContext;
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
 * Caractères de contrôle dans les champs texte reçus par les routes d'intégration (lot 2, étape G15) : U+0000 à U+001F
 * et U+007F refusés par un 400 au format de la route ({@code invalid_request} pour {@code /oauth/*},
 * {@code VALIDATION_FAILED} ailleurs), jamais un 500, sans rien enregistrer ni auditer ; tabulation et sauts de ligne
 * permis dans les seuls motifs libres. Aucune valeur reçue n'est reprise dans la réponse ni dans les journaux (TRACE).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class IntegrationControlCharactersIT {

	private static final String PAR = "/api/integration/v1/oauth/par";

	private static final String TOKEN = "/api/integration/v1/oauth/token";

	private static final String LINK = "/api/integration/v1/link";

	private static final String REVOKE = "/api/integration/v1/link/revoke";

	private static final String REQUESTS = "/api/integration-links/authorization-requests";

	private static final String LINKS = "/api/integration-links";

	/** Refusés dans tous les champs, motifs libres compris. */
	private static final List<String> CONTROLS = List.of("\u0000", "\u0001", "\u000B", "\u000C", "\u001B", "\u001F", "\u007F");

	/** Tabulation et sauts de ligne : refusés hors des motifs libres. */
	private static final List<String> LINE_BREAKS = List.of("\t", "\n", "\r");

	private static final List<String> PAR_FIELDS = List.of("response_type", "redirect_uri", "scope", "state", "code_challenge",
			"code_challenge_method", "shop_id", "shop_name");

	private static final List<String> CODE_FIELDS = List.of("grant_type", "code", "redirect_uri", "code_verifier");

	private static final List<String> CREDENTIALS_FIELDS = List.of("grant_type", "link_id", "link_secret", "scope");

	private static final SecureRandom RANDOM = new SecureRandom();

	/** Loggers limités à INFO par application.yml, laissés à ce niveau pendant la capture en TRACE. */
	private static final Set<String> CAPPED_LOGGERS = Set.of("org.postgresql", "org.springframework.web.method.HandlerMethod");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private PlatformDb platform;

	@Autowired
	private LinkRegistry registry;

	@Autowired
	private IntegrationTokenService tokens;

	@Autowired
	private JwtTokenService userTokens;

	@Autowired
	private UserAccountService accounts;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private JsonMapper json;

	private TestLinks links;

	private TenantRef company;

	private String clientSecret;

	private TestLinks.Client client;

	private UUID admin;

	private UUID mainWarehouse;

	private final List<String> sensitive = java.util.Collections.synchronizedList(new ArrayList<>());

	private final Map<Logger, Level> levels = new LinkedHashMap<>();

	private ListAppender<ILoggingEvent> logs;

	@BeforeEach
	void setUp() {
		this.links = new TestLinks(this.platform, this.registry);
		this.company = new TenantRef(this.links.legacyCompany(), "test");
		this.clientSecret = remember(randomToken());
		this.client = this.links.client(this.clientSecret, null, Set.of("catalog:read", "orders:read"));
		String username = "g15.admin." + UUID.randomUUID().toString().substring(0, 6);
		this.admin = TenantContext.callAs(this.company, () -> {
			UUID created = this.jdbc.queryForObject(
					"insert into app_user (username, last_name, must_change_password) values (?, 'Essai G15', false) returning id",
					UUID.class, username);
			this.jdbc.update("insert into user_role (user_id, role_id) select ?, id from role where code = 'ADMIN'", created);
			return created;
		});
		this.mainWarehouse = TenantContext.callAs(this.company,
				() -> this.jdbc.queryForObject("select id from warehouse where main", UUID.class));
		LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
		for (Logger logger : context.getLoggerList()) {
			// Sauf les loggers que la configuration limite à INFO parce qu'ils citent les valeurs reçues : pilote PostgreSQL
			// (G12) et appel des contrôleurs par Spring MVC (G15) ; le test vérifie justement que ces limites protègent.
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
			List<String> messages = this.logs.list.stream().map(IntegrationControlCharactersIT::render).toList();
			assertThat(messages).as("aucune valeur reçue ni secret dans les journaux")
				.allSatisfy(message -> this.sensitive.forEach(value -> assertThat(message).doesNotContain(value)));
		}
		finally {
			this.sensitive.clear();
			this.links.cleanUp();
			TenantContext.runAs(this.company, () -> this.jdbc.update("delete from app_user where id = ?", this.admin));
		}
	}

	// --- /oauth/par -----------------------------------------------------------------------------------------------

	static Stream<Arguments> parFields() {
		return PAR_FIELDS.stream().flatMap(field -> Stream.concat(CONTROLS.stream(), LINE_BREAKS.stream())
			.map(control -> Arguments.of(field, control)));
	}

	@ParameterizedTest(name = "{0} {index}")
	@MethodSource("parFields")
	void parRefusesAControlCharacterInAnyFieldWithoutRecordingAnything(String field, String control) throws Exception {
		String shop = "shop-g15-par-" + UUID.randomUUID().toString().substring(0, 8);
		Map<String, String> params = parParams(shop);
		String marker = marker();
		params.put(field, params.get(field) + control + marker);
		long audit = auditRows();

		MockHttpServletResponse response = this.mvc.perform(form(post(PAR), params)).andReturn().getResponse();
		assertOAuthInvalidRequest(response, marker);
		assertThat(requestsOfClient()).as("aucune demande enregistrée").isZero();
		assertThat(auditRows()).as("aucun audit").isEqualTo(audit);
	}

	@Test
	void parRefusesAControlCharacterInAParameterNameAndNeverEchoesAnUnknownName() throws Exception {
		String shop = "shop-g15-par-name";
		String marker = marker();
		Map<String, String> params = parParams(shop);
		params.put("extra\u0000" + marker, "1");
		assertOAuthInvalidRequest(this.mvc.perform(form(post(PAR), params)).andReturn().getResponse(), marker);

		String unknown = "inconnu-" + marker();
		MockHttpServletRequestBuilder repeated = form(post(PAR), parParams(shop)).param(unknown, "a", "b");
		MockHttpServletResponse response = this.mvc.perform(repeated).andReturn().getResponse();
		assertThat(response.getStatus()).isEqualTo(400);
		assertThat(response.getContentAsString()).as("nom de paramètre inconnu non repris").doesNotContain(unknown);
		assertThat(requestsOfShop(shop)).isZero();
	}

	@Test
	void parStillAcceptsNormalValues() throws Exception {
		Map<String, String> params = parParams("shop-g15-par-ok");
		params.put("shop_name", "Boutique d'Awa — Épicerie « fine » 🛒");
		this.mvc.perform(form(post(PAR), params)).andExpect(status().isCreated())
			.andExpect(jsonPath("$.request_uri").exists());
		assertThat(requestsOfShop("shop-g15-par-ok")).isEqualTo(1);
	}

	// --- /oauth/token : authorization_code --------------------------------------------------------------------------

	static Stream<Arguments> codeFields() {
		return CODE_FIELDS.stream().flatMap(field -> Stream.of("\u0000", "\u001F", "\u007F", "\n")
			.map(control -> Arguments.of(field, control)));
	}

	@ParameterizedTest(name = "{0} {index}")
	@MethodSource("codeFields")
	void codeExchangeRefusesAControlCharacterWithoutConsumingTheCode(String field, String control) throws Exception {
		Pending pending = pendingCode("shop-g15-code-" + UUID.randomUUID().toString().substring(0, 8));
		Map<String, String> params = codeParams(pending);
		String marker = marker();
		params.put(field, params.get(field) + control + marker);
		long audit = auditRows();

		assertOAuthInvalidRequest(this.mvc.perform(form(post(TOKEN), params)).andReturn().getResponse(), marker);
		assertThat(auditRows()).as("aucun audit").isEqualTo(audit);
		assertThat(this.registry.find(pending.linkId()).orElseThrow().status()).isEqualTo(LinkStatus.PENDING);
		assertThat(codeUsed(pending.code())).as("code non consommé").isFalse();

		// Le code reste utilisable avec les bons paramètres.
		JsonNode body = read(this.mvc.perform(form(post(TOKEN), codeParams(pending))).andExpect(status().isOk()));
		remember(body.get("access_token").asString());
		remember(body.get("link_secret").asString());
	}

	// --- /oauth/token : client_credentials ------------------------------------------------------------------------

	static Stream<Arguments> credentialsFields() {
		return CREDENTIALS_FIELDS.stream().flatMap(field -> Stream.of("\u0000", "\u001F", "\u007F", "\t")
			.map(control -> Arguments.of(field, control)));
	}

	@ParameterizedTest(name = "{0} {index}")
	@MethodSource("credentialsFields")
	void clientCredentialsRefusesAControlCharacterWithoutAuditing(String field, String control) throws Exception {
		Link pending = this.links.pending(this.client, this.company.companyId(),
				"shop-g15-cc-" + UUID.randomUUID().toString().substring(0, 8), Set.of("catalog:read"));
		ActivatedLink activated = this.registry.activate(pending.id());
		String secret = remember(activated.secret());
		Map<String, String> params = new LinkedHashMap<>();
		params.put("grant_type", "client_credentials");
		params.put("link_id", pending.id().toString());
		params.put("link_secret", secret);
		params.put("scope", "catalog:read");
		String marker = marker();
		params.put(field, params.get(field) + control + marker);
		long audit = auditRows();

		assertOAuthInvalidRequest(this.mvc.perform(form(post(TOKEN), params)).andReturn().getResponse(), marker);
		assertThat(auditRows()).as("aucun audit").isEqualTo(audit);

		Map<String, String> valid = new LinkedHashMap<>(params);
		valid.put(field, field.equals("grant_type") ? "client_credentials" : field.equals("link_id") ? pending.id().toString()
				: field.equals("link_secret") ? secret : "catalog:read");
		JsonNode body = read(this.mvc.perform(form(post(TOKEN), valid)).andExpect(status().isOk()));
		remember(body.get("access_token").asString());
	}

	// --- Authentification du client et jeton de liaison ---------------------------------------------------------

	/**
	 * Identifiant client illisible (caractère de contrôle) : échec d'authentification du client ({@code 401
	 * invalid_client}, RFC 6749 § 5.2), jamais un 500 ; l'échec reste tracé (G12), sans l'identifiant annoncé.
	 */
	@Test
	void aControlCharacterInTheClientCredentialsIsAnAuthenticationFailureNeverA500() throws Exception {
		String marker = marker();
		for (String announced : List.of(this.client.clientId() + "\u0000" + marker, this.client.clientId() + "\u007F" + marker)) {
			MockHttpServletRequestBuilder request = post(PAR).contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.header(HttpHeaders.AUTHORIZATION, basic(announced, this.clientSecret));
			parParams("shop-g15-basic").forEach(request::param);
			MockHttpServletResponse response = this.mvc.perform(request).andReturn().getResponse();
			assertThat(response.getStatus()).isEqualTo(401);
			assertThat(this.json.readTree(response.getContentAsString()).get("error").asString()).isEqualTo("invalid_client");
			assertThat(response.getContentAsString()).doesNotContain(marker);
		}
		assertThat(this.platform.jdbc().sql("""
				select count(*) from platform.integration_audit_log
				where action = 'CLIENT_AUTHENTICATION_FAILED' and announced_client like :prefix""")
			.param("prefix", this.client.clientId() + "%").query(Long.class).single()).as("identifiant annoncé non tracé").isZero();
		assertThat(requestsOfShop("shop-g15-basic")).isZero();
	}

	/** En-tête illisible : refusé par le pare-feu de Spring Security (400) avant toute route, jamais un 500. */
	@Test
	void aControlCharacterInTheLinkTokenIsRefusedBeforeAnyRoute() throws Exception {
		this.mvc.perform(get(LINK).header(HttpHeaders.AUTHORIZATION, "Bearer abc\u0000def")).andExpect(status().isBadRequest());
	}

	// --- POST /link/revoke : motif libre ---------------------------------------------------------------------------

	@ParameterizedTest
	@MethodSource("controls")
	void revocationRefusesAControlCharacterInTheReasonWithoutRevokingOrAuditing(String control) throws Exception {
		Link link = this.links.active(this.client, this.company.companyId(),
				"shop-g15-revoke-" + UUID.randomUUID().toString().substring(0, 8), Set.of("catalog:read"));
		String token = remember(this.tokens.issue(link.id(), this.client.clientId(), Set.of("catalog:read")).value());
		String marker = marker();
		long audit = auditRows();

		MockHttpServletResponse response = this.mvc.perform(bearer(jsonPost(REVOKE, Map.of("reason", "Motif " + control + marker)),
				token)).andReturn().getResponse();
		assertApiValidationFailed(response, marker);
		assertThat(this.registry.find(link.id()).orElseThrow().status()).isEqualTo(LinkStatus.ACTIVE);
		assertThat(auditRows()).as("aucun audit").isEqualTo(audit);
	}

	static Stream<String> controls() {
		return CONTROLS.stream();
	}

	@Test
	void revocationAcceptsTabsAndLineBreaksInTheReason() throws Exception {
		Link link = this.links.active(this.client, this.company.companyId(), "shop-g15-revoke-text", Set.of("catalog:read"));
		String token = remember(this.tokens.issue(link.id(), this.client.clientId(), Set.of("catalog:read")).value());
		String reason = remember("Fermeture de la boutique\r\n\tLigne 2\nfin " + marker());

		this.mvc.perform(bearer(jsonPost(REVOKE, Map.of("reason", reason)), token)).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REVOKED"));
		assertThat(this.platform.jdbc().sql("""
				select reason from platform.integration_audit_log where link_id = :link and action = 'LINK_REVOKED'""")
			.param("link", link.id()).query(String.class).single()).isEqualTo(reason);
	}

	// --- Écrans de GestStock : consentement et gestion des liaisons -------------------------------------------------

	static Stream<Arguments> consentFields() {
		return Stream.of("lookup", "deny", "approve", "approve-scope").flatMap(route -> Stream.of("\u0000", "\u007F", "\t", "\n")
			.map(control -> Arguments.of(route, control)));
	}

	@ParameterizedTest(name = "{0} {index}")
	@MethodSource("consentFields")
	void consentRoutesRefuseAControlCharacterWithoutDecidingOrAuditing(String route, String control) throws Exception {
		String shop = "shop-g15-consent-" + UUID.randomUUID().toString().substring(0, 8);
		String requestUri = par(shop);
		// Court : la portée trafiquée reste sous les 30 caractères permis (sinon refusée pour sa longueur).
		String marker = remember("G15" + UUID.randomUUID().toString().substring(0, 6));
		String tampered = requestUri + control + marker;
		long audit = auditRows();
		int linkWarehouses = linkWarehouseRows();

		MockHttpServletRequestBuilder request = switch (route) {
			case "lookup" -> jsonPost(REQUESTS + "/lookup", Map.of("requestUri", tampered));
			case "deny" -> jsonPost(REQUESTS + "/deny", Map.of("requestUri", tampered));
			case "approve" -> jsonPost(REQUESTS + "/approve",
					Map.of("requestUri", tampered, "scopes", List.of("catalog:read"), "warehouseIds", List.of(this.mainWarehouse)));
			default -> jsonPost(REQUESTS + "/approve", Map.of("requestUri", requestUri, "scopes",
					List.of("catalog:read" + control + marker), "warehouseIds", List.of(this.mainWarehouse)));
		};
		assertApiValidationFailed(this.mvc.perform(bearer(request, adminToken())).andReturn().getResponse(), marker);
		assertThat(decision(requestUri)).as("demande non décidée").isEqualTo("NONE");
		assertThat(linksOfShop(shop)).as("aucune liaison").isZero();
		assertThat(linkWarehouseRows()).as("aucun dépôt de liaison").isEqualTo(linkWarehouses);
		assertThat(auditRows()).as("aucun audit").isEqualTo(audit);

		// La demande reste consultable.
		this.mvc.perform(bearer(jsonPost(REQUESTS + "/lookup", Map.of("requestUri", requestUri)), adminToken()))
			.andExpect(status().isOk());
	}

	/**
	 * Identifiant de liaison dans le chemin : un caractère de contrôle, même en fin de valeur (que la conversion en UUID
	 * retirerait), est refusé ({@code VALIDATION_FAILED}) avant la route ; {@code %00} est refusé plus tôt encore par le
	 * pare-feu de Spring Security (400 sans corps). Dépôts (UUID dans le corps JSON) : erreur de format du corps,
	 * {@code MALFORMED_REQUEST}.
	 */
	@Test
	void managementRoutesRefuseAControlCharacterInAnIdentifier() throws Exception {
		Link link = this.links.active(this.client, this.company.companyId(), "shop-g15-manage", Set.of("catalog:read"));
		long audit = auditRows();
		String id = link.id().toString();
		for (String action : List.of("suspend", "revoke", "reactivate")) {
			for (String tampered : List.of(id + "\u0000", id + "\u001F", "\u0001" + id, id.substring(0, 8) + "\u007F" + id.substring(8))) {
				MockHttpServletResponse response = this.mvc.perform(bearer(post(LINKS + "/{id}/" + action, tampered), adminToken())
					.contentType(MediaType.APPLICATION_JSON).content("{\"warehouseIds\":[\"" + this.mainWarehouse + "\"]}"))
					.andReturn().getResponse();
				String body = response.getContentAsString();
				assertThat(response.getStatus()).as(action + " : " + body).isEqualTo(400);
				if (tampered.indexOf('\u0000') < 0) {
					assertThat(this.json.readTree(body).get("code").asString()).as(action).isEqualTo("VALIDATION_FAILED");
				}
			}
		}
		assertThat(this.registry.find(link.id()).orElseThrow().status()).isEqualTo(LinkStatus.ACTIVE);
		this.registry.suspend(link.id(), SuspensionReason.MANUAL);
		String warehouse = this.mainWarehouse.toString();
		this.mvc.perform(bearer(post(LINKS + "/{id}/reactivate", link.id()), adminToken()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"warehouseIds\":[\"" + warehouse.substring(0, 8) + "\\u0000" + warehouse.substring(8) + "\"]}"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
		assertThat(this.registry.find(link.id()).orElseThrow().status()).isEqualTo(LinkStatus.SUSPENDED);
		assertThat(auditRows()).isEqualTo(audit);
	}

	// --- Valeurs refusées par la validation (@Size…) : jamais dans les journaux --------------------------------------

	static Stream<String> validatedFields() {
		return Stream.of("lookup.requestUri", "deny.requestUri", "approve.requestUri", "approve.scopes[1]", "approve.scopes",
				"approve.warehouseIds", "reactivate.warehouseIds");
	}

	/**
	 * Valeur refusée par la validation du corps (G15, complément) : l'exception de validation de Spring porte la valeur
	 * refusée, journalisée en DEBUG et en TRACE. Refus inchangé (400 VALIDATION_FAILED, erreurs par champ), aucune partie
	 * de la valeur dans les journaux, en TRACE compris (vérifié après chaque test).
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("validatedFields")
	void aValueRefusedByValidationIsNeverLogged(String target) throws Exception {
		String marker = marker();
		String requestUri = remember(OAuthService.REQUEST_URI_PREFIX + marker + "x".repeat(200));
		String longScope = marker + "-portee-trop-longue";
		UUID extraWarehouse = UUID.randomUUID();
		remember(extraWarehouse.toString());
		List<UUID> tooManyWarehouses = new ArrayList<>();
		tooManyWarehouses.add(extraWarehouse);
		for (int i = 0; i < 100; i++) {
			tooManyWarehouses.add(this.mainWarehouse);
		}
		List<String> tooManyScopes = List.of("catalog:read", "orders:read", "a", "b", "c", "d", marker);
		String validUri = OAuthService.REQUEST_URI_PREFIX + randomToken();
		UUID link = UUID.randomUUID();

		MockHttpServletRequestBuilder request = switch (target) {
			case "lookup.requestUri" -> jsonPost(REQUESTS + "/lookup", Map.of("requestUri", requestUri));
			case "deny.requestUri" -> jsonPost(REQUESTS + "/deny", Map.of("requestUri", requestUri));
			case "approve.requestUri" -> jsonPost(REQUESTS + "/approve", Map.of("requestUri", requestUri, "scopes",
					List.of("catalog:read"), "warehouseIds", List.of(this.mainWarehouse)));
			case "approve.scopes[1]" -> jsonPost(REQUESTS + "/approve", Map.of("requestUri", validUri, "scopes",
					List.of("catalog:read", longScope), "warehouseIds", List.of(this.mainWarehouse)));
			case "approve.scopes" -> jsonPost(REQUESTS + "/approve", Map.of("requestUri", validUri, "scopes", tooManyScopes,
					"warehouseIds", List.of(this.mainWarehouse)));
			case "approve.warehouseIds" -> jsonPost(REQUESTS + "/approve", Map.of("requestUri", validUri, "scopes",
					List.of("catalog:read"), "warehouseIds", tooManyWarehouses));
			default -> jsonPost(LINKS + "/" + link + "/reactivate", Map.of("warehouseIds", tooManyWarehouses));
		};
		long audit = auditRows();
		MockHttpServletResponse response = this.mvc.perform(bearer(request, adminToken())).andReturn().getResponse();

		String field = target.substring(target.indexOf('.') + 1);
		assertThat(response.getStatus()).isEqualTo(400);
		JsonNode body = this.json.readTree(response.getContentAsString());
		assertThat(new java.util.TreeSet<>(body.propertyNames())).as("format ApiError").isEqualTo(new java.util.TreeSet<>(List.of(
				"type", "title", "status", "code", "message", "detail", "errors", "details", "traceId", "timestamp", "path")));
		assertThat(body.get("code").asString()).isEqualTo("VALIDATION_FAILED");
		assertThat(body.get("errors")).hasSize(1);
		JsonNode violation = body.get("errors").get(0);
		assertThat(violation.get("field").asString()).isEqualTo(field);
		assertThat(violation.get("message").asString()).isNotBlank();
		assertThat(body.get("message").asString()).isEqualTo("Données invalides : " + field + " " + violation.get("message").asString());
		assertThat(response.getContentAsString()).doesNotContain(marker).doesNotContain(extraWarehouse.toString());
		assertThat(auditRows()).isEqualTo(audit);
	}

	// --- JSON mal formé : aucun extrait de la valeur reçue ------------------------------------------------------------

	static Stream<String> malformedBodies() {
		return Stream.of("approve.uuid", "reactivate.uuid", "approve.number-for-uuid", "lookup.array-for-string",
				"lookup.object-for-string", "lookup.truncated", "deny.syntax", "revoke.truncated", "revoke.array-for-string",
				"revoke.syntax");
	}

	/**
	 * JSON mal formé (UUID illisible, type incorrect, syntaxe invalide, corps tronqué) : refus inchangé (400
	 * MALFORMED_REQUEST, format ApiError), aucun extrait de la valeur reçue dans la réponse ni dans les journaux, en
	 * TRACE compris (vérifié après chaque test). Le message d'erreur de Jackson cite la valeur ou le jeton reçu. Un
	 * nombre à la place d'une chaîne n'est pas un refus : Jackson le convertit en texte.
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("malformedBodies")
	void aMalformedBodyNeverLeaksTheReceivedValue(String target) throws Exception {
		String marker = marker();
		String digits = remember("9" + String.format("%017d", RANDOM.nextLong(100_000_000_000_000_000L)));
		String validUri = OAuthService.REQUEST_URI_PREFIX + randomToken();
		String warehouses = "\"warehouseIds\":[\"" + this.mainWarehouse + "\"]";
		String body = switch (target) {
			case "approve.uuid" -> "{\"requestUri\":\"" + validUri + "\",\"scopes\":[\"catalog:read\"],\"warehouseIds\":[\"" + marker + "\"]}";
			case "reactivate.uuid" -> "{\"warehouseIds\":[\"" + marker + "\"]}";
			case "approve.number-for-uuid" -> "{\"requestUri\":\"" + validUri + "\",\"scopes\":[\"catalog:read\"],\"warehouseIds\":[" + digits + "]}";
			case "lookup.array-for-string" -> "{\"requestUri\":[\"" + marker + "\"," + digits + "]}";
			case "lookup.object-for-string" -> "{\"requestUri\":{\"valeur\":\"" + marker + "\"}}";
			case "lookup.truncated" -> "{\"requestUri\":\"" + marker;
			case "deny.syntax" -> "{\"requestUri\":" + marker + "}";
			case "revoke.truncated" -> "{\"reason\":\"" + marker;
			case "revoke.array-for-string" -> "{\"reason\":[\"" + marker + "\"]}";
			default -> "{\"reason\":" + marker + ", " + warehouses + "}";
		};
		String path = switch (target.substring(0, target.indexOf('.'))) {
			case "approve" -> REQUESTS + "/approve";
			case "reactivate" -> LINKS + "/" + UUID.randomUUID() + "/reactivate";
			case "lookup" -> REQUESTS + "/lookup";
			case "deny" -> REQUESTS + "/deny";
			default -> REVOKE;
		};
		String token;
		Link link = null;
		if (path.equals(REVOKE)) {
			link = this.links.active(this.client, this.company.companyId(), "shop-g15-malformed-" + UUID.randomUUID().toString()
				.substring(0, 8), Set.of("catalog:read"));
			token = remember(this.tokens.issue(link.id(), this.client.clientId(), Set.of("catalog:read")).value());
		}
		else {
			token = adminToken();
		}
		long audit = auditRows();

		MockHttpServletResponse response = this.mvc.perform(bearer(post(path), token).contentType(MediaType.APPLICATION_JSON)
			.content(body)).andReturn().getResponse();
		assertThat(response.getStatus()).isEqualTo(400);
		JsonNode error = this.json.readTree(response.getContentAsString());
		assertThat(new java.util.TreeSet<>(error.propertyNames())).as("format ApiError").isEqualTo(new java.util.TreeSet<>(List.of(
				"type", "title", "status", "code", "message", "detail", "errors", "details", "traceId", "timestamp", "path")));
		assertThat(error.get("code").asString()).isEqualTo("MALFORMED_REQUEST");
		assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		assertThat(response.getContentAsString()).doesNotContain(marker).doesNotContain(digits);
		assertThat(auditRows()).isEqualTo(audit);
		if (link != null) {
			assertThat(this.registry.find(link.id()).orElseThrow().status()).isEqualTo(LinkStatus.ACTIVE);
		}
	}

	/**
	 * Tolérance retenue (G15) : Jackson retire les caractères de contrôle de début et de fin d'un UUID du corps JSON ;
	 * {@code "<uuid>\u0000"} désigne le dépôt {@code <uuid>}, enregistré sans le caractère, jamais un 500.
	 */
	@Test
	void aTrailingControlCharacterAfterAWarehouseUuidIsToleratedAndTheCleanUuidIsStored() throws Exception {
		Link link = this.links.active(this.client, this.company.companyId(), "shop-g15-uuid", Set.of("catalog:read"));
		this.registry.suspend(link.id(), SuspensionReason.MANUAL);

		this.mvc.perform(bearer(post(LINKS + "/{id}/reactivate", link.id()), adminToken()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"warehouseIds\":[\"" + this.mainWarehouse + "\\u0000\"]}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
		List<String> stored = TenantContext.callAs(this.company, () -> this.jdbc.queryForList(
				"select warehouse_id::text from integration_link_warehouse where link_id = ?", String.class, link.id()));
		assertThat(stored).containsExactly(this.mainWarehouse.toString());
		TenantContext.runAs(this.company,
				() -> this.jdbc.update("delete from integration_link_warehouse where link_id = ?", link.id()));
	}

	// --- Outils ---------------------------------------------------------------------------------------------

	private record Pending(UUID linkId, String code, String verifier) {
	}

	private void assertOAuthInvalidRequest(MockHttpServletResponse response, String marker) throws Exception {
		assertThat(response.getStatus()).as("400").isEqualTo(400);
		JsonNode body = this.json.readTree(response.getContentAsString());
		assertThat(body.get("error").asString()).isEqualTo("invalid_request");
		assertThat(response.getContentAsString()).as("valeur reçue non reprise").doesNotContain(marker);
		assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
	}

	private void assertApiValidationFailed(MockHttpServletResponse response, String marker) throws Exception {
		assertThat(response.getStatus()).as("400").isEqualTo(400);
		assertThat(this.json.readTree(response.getContentAsString()).get("code").asString()).isEqualTo("VALIDATION_FAILED");
		assertThat(response.getContentAsString()).as("valeur reçue non reprise").doesNotContain(marker);
	}

	private Map<String, String> parParams(String shop) throws Exception {
		Map<String, String> params = new LinkedHashMap<>();
		params.put("response_type", "code");
		params.put("redirect_uri", TestLinks.REDIRECT_URI);
		params.put("scope", "catalog:read");
		params.put("state", randomToken());
		params.put("code_challenge", challenge(remember(randomToken() + "-verifier")));
		params.put("code_challenge_method", "S256");
		params.put("shop_id", shop);
		params.put("shop_name", "Boutique " + shop);
		return params;
	}

	private String par(String shop) throws Exception {
		JsonNode body = read(this.mvc.perform(form(post(PAR), parParams(shop))).andExpect(status().isCreated()));
		return remember(body.get("request_uri").asString());
	}

	private Map<String, String> codeParams(Pending pending) {
		Map<String, String> params = new LinkedHashMap<>();
		params.put("grant_type", "authorization_code");
		params.put("code", pending.code());
		params.put("redirect_uri", TestLinks.REDIRECT_URI);
		params.put("code_verifier", pending.verifier());
		return params;
	}

	/** Liaison en attente et code d'autorisation, comme après une approbation. */
	private Pending pendingCode(String shop) throws Exception {
		String verifier = remember(randomToken() + "-verifier");
		UUID request = this.platform.jdbc().sql("""
				insert into platform.integration_authorization_request (request_uri_hash, client_id, external_shop_id, shop_name,
				  redirect_uri, code_challenge, scopes, state, expires_at, decision, used_at)
				values (:hash, :client, :shop, :name, :redirect, :challenge, ARRAY['catalog:read']::varchar[], repeat('s', 43),
				  now() + interval '10 minutes', 'APPROVED', now())
				returning id""")
			.param("hash", LinkRegistry.sha256Hex(UUID.randomUUID().toString())).param("client", this.client.id())
			.param("shop", shop).param("name", "Boutique " + shop).param("redirect", TestLinks.REDIRECT_URI)
			.param("challenge", challenge(verifier)).query(UUID.class).single();
		Link pending = this.registry.createPending(request, this.company.companyId(), new Authorizer(this.admin, "admin"),
				Set.of("catalog:read"));
		String code = remember(randomToken());
		this.platform.jdbc().sql("""
				insert into platform.integration_authorization_code (code_hash, request_id, link_id, expires_at)
				values (:hash, :request, :link, now() + interval '5 minutes')""")
			.param("hash", LinkRegistry.sha256Hex(code)).param("request", request).param("link", pending.id()).update();
		return new Pending(pending.id(), code, verifier);
	}

	private boolean codeUsed(String code) {
		return this.platform.jdbc().sql("select used_at is not null from platform.integration_authorization_code where code_hash = :h")
			.param("h", LinkRegistry.sha256Hex(code)).query(Boolean.class).single();
	}

	private long auditRows() {
		return this.platform.jdbc().sql("select count(*) from platform.integration_audit_log").query(Long.class).single();
	}

	/** Demandes du client de ce test (un client par test) ; sans valeur reçue dans la requête SQL, tracée en TRACE. */
	private int requestsOfClient() {
		return this.platform.jdbc().sql("select count(*) from platform.integration_authorization_request where client_id = :c")
			.param("c", this.client.id()).query(Integer.class).single();
	}

	private int requestsOfShop(String shop) {
		return this.platform.jdbc().sql("""
				select count(*) from platform.integration_authorization_request where client_id = :c and external_shop_id = :s""")
			.param("c", this.client.id()).param("s", shop).query(Integer.class).single();
	}

	private int linksOfShop(String shop) {
		return this.platform.jdbc().sql("select count(*) from platform.integration_link where client_id = :c and external_shop_id = :s")
			.param("c", this.client.id()).param("s", shop).query(Integer.class).single();
	}

	private String decision(String requestUri) {
		return this.platform.jdbc().sql("""
				select coalesce(decision, 'NONE') from platform.integration_authorization_request where request_uri_hash = :hash""")
			.param("hash", LinkRegistry.sha256Hex(requestUri)).query(String.class).single();
	}

	private int linkWarehouseRows() {
		return TenantContext.callAs(this.company,
				() -> this.jdbc.queryForObject("select count(*) from integration_link_warehouse", Integer.class));
	}

	private String adminToken() {
		return remember(TenantContext.callAs(this.company,
				() -> this.userTokens.issue(this.accounts.findById(this.admin).orElseThrow()).value()));
	}

	private MockHttpServletRequestBuilder form(MockHttpServletRequestBuilder request, Map<String, String> params) {
		request.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.header(HttpHeaders.AUTHORIZATION, basic(this.client.clientId(), this.clientSecret));
		params.forEach(request::param);
		return request;
	}

	private MockHttpServletRequestBuilder jsonPost(String path, Map<String, Object> body) {
		return post(path).contentType(MediaType.APPLICATION_JSON).content(this.json.writeValueAsString(body));
	}

	private static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder request, String token) {
		return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
	}

	private JsonNode read(ResultActions actions) throws Exception {
		return this.json.readTree(actions.andReturn().getResponse().getContentAsString());
	}

	/** Valeur unique placée après le caractère de contrôle : ni dans la réponse, ni dans les journaux. */
	private String marker() {
		return remember("G15marq" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
	}

	private String remember(String value) {
		this.sensitive.add(value);
		return value;
	}

	private String basic(String clientId, String secret) {
		return "Basic " + remember(Base64.getEncoder().encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.UTF_8)));
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

}
