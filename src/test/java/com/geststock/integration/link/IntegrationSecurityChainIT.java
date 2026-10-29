package com.geststock.integration.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.support.PostgresTestConfiguration;
import com.geststock.support.WithPermissionsSecurityContextFactory;
import com.geststock.user.UserService;
import com.geststock.user.dto.CreateUserRequest;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import tools.jackson.databind.json.JsonMapper;

/**
 * Chaîne de sécurité de l'intégration (lot 2, étape G6 ; v13-D6) : {@code /api/integration/v1/**} est servi par une
 * chaîne dédiée, prioritaire, sans état, sans CORS, qui authentifie un jeton de liaison, exige la portée déclarée par
 * {@link RequiresScope} et refuse tout le reste. La chaîne principale ne voit jamais ces chemins et reste inchangée.
 * Les routes de test ci-dessous n'existent que dans ce contexte.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({ PostgresTestConfiguration.class, IntegrationSecurityChainIT.ScopedTestController.class })
class IntegrationSecurityChainIT {

	private static final String CATALOG = "/api/integration/v1/test-chain/catalog";

	private static final String ORDERS = "/api/integration/v1/test-chain/orders";

	private static final String UNSCOPED = "/api/integration/v1/test-chain/unscoped";

	private static final String NOWHERE = "/api/integration/v1/test-chain/nowhere";

	private static final String PASSWORD = "Optique2026";

	private static final UUID LINK = UUID.randomUUID();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private IntegrationTokenService tokens;

	@Autowired
	private IntegrationLinkProperties properties;

	@Autowired
	private FilterChainProxy filterChainProxy;

	@Autowired
	private UserService users;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private JsonMapper json;

	@AfterEach
	void deleteTestUsers() {
		this.jdbc.update("delete from app_user where username like 'intsec\\_%'");
	}

	// --- Authentification ---------------------------------------------------------------------------------

	@Test
	void aRequestWithoutTokenIsUnauthorized() throws Exception {
		assertUnauthorized(this.mvc.perform(get(CATALOG)));
	}

	@Test
	void aMalformedExpiredOrForeignTokenIsUnauthorized() throws Exception {
		assertUnauthorized(this.mvc.perform(get(CATALOG).header(HttpHeaders.AUTHORIZATION, "Bearer abc")));
		assertUnauthorized(this.mvc.perform(get(CATALOG).header(HttpHeaders.AUTHORIZATION, "Basic abc")));
		Instant past = Instant.now().minusSeconds(3600);
		assertUnauthorized(this.mvc.perform(get(CATALOG).header(HttpHeaders.AUTHORIZATION,
				"Bearer " + forge(testKey("test-1"), "test-1", past, past.plusSeconds(900), "catalog:read"))));
		byte[] foreignKey = "a-key-that-is-not-listed-in-the-configuration".getBytes(StandardCharsets.UTF_8);
		assertUnauthorized(this.mvc.perform(get(CATALOG).header(HttpHeaders.AUTHORIZATION,
				"Bearer " + forge(foreignKey, "test-1", Instant.now(), Instant.now().plusSeconds(900), "catalog:read"))));
	}

	@Test
	void aValidUserTokenIsRefusedByTheIntegrationChainButStillWorksOnTheMainChain() throws Exception {
		String userToken = userToken("intsec_admin");

		this.mvc.perform(get("/api/products").header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
			.andExpect(status().isOk());
		assertUnauthorized(this.mvc.perform(get(CATALOG).header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)));
		assertUnauthorized(this.mvc.perform(get(NOWHERE).header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)));
	}

	@Test
	void aValidLinkTokenIsRefusedByTheMainChain() throws Exception {
		this.mvc.perform(get("/api/products").header(HttpHeaders.AUTHORIZATION, "Bearer " + linkToken("catalog:read")))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
	}

	// --- Portées --------------------------------------------------------------------------------------------

	@Test
	void aLinkTokenWithTheScopeReachesTheEndpointWithItsPrincipal() throws Exception {
		this.mvc.perform(get(CATALOG).header(HttpHeaders.AUTHORIZATION, "Bearer " + linkToken("catalog:read")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.linkId").value(LINK.toString()))
			.andExpect(jsonPath("$.clientId").value("jcmb-test"))
			.andExpect(jsonPath("$.scopes[0]").value("catalog:read"))
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
	}

	@Test
	void aLinkTokenWithoutTheScopeIsForbidden() throws Exception {
		this.mvc.perform(get(CATALOG).header(HttpHeaders.AUTHORIZATION, "Bearer " + linkToken("orders:read")))
			.andExpect(status().isForbidden())
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\""))
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
			.andExpect(jsonPath("$.code").value("INSUFFICIENT_SCOPE"))
			.andExpect(jsonPath("$.status").value(403));
	}

	@Test
	void ordersWriteImpliesOrdersRead() throws Exception {
		this.mvc.perform(get(ORDERS).header(HttpHeaders.AUTHORIZATION, "Bearer " + linkToken("orders:write")))
			.andExpect(status().isOk());
		this.mvc.perform(get(ORDERS).header(HttpHeaders.AUTHORIZATION, "Bearer " + linkToken("catalog:read")))
			.andExpect(status().isForbidden());
	}

	// --- Refus par défaut ------------------------------------------------------------------------------------

	@Test
	void anIntegrationPathWithoutAScopedControllerIsDenied() throws Exception {
		String token = linkToken("catalog:read", "orders:write");

		this.mvc.perform(get(NOWHERE).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
		this.mvc.perform(get(UNSCOPED).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
		assertUnauthorized(this.mvc.perform(get(NOWHERE)));
	}

	@Test
	void theClientAuthenticatedRoutesAreDeclaredButRefusedUntilTheyExist() throws Exception {
		assertThat(IntegrationSecurityConfig.CLIENT_AUTHENTICATED_PATHS)
			.containsExactly("/api/integration/v1/oauth/token", "/api/integration/v1/oauth/par");
		for (String path : IntegrationSecurityConfig.CLIENT_AUTHENTICATED_PATHS) {
			assertUnauthorized(this.mvc.perform(post(path)));
			assertUnauthorized(this.mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + linkToken("catalog:read"))));
		}
	}

	// --- Chaîne : ordre, CORS, en-têtes ---------------------------------------------------------------------

	@Test
	void theIntegrationChainComesFirstAndAlonePerformsIntegrationPaths() {
		List<SecurityFilterChain> chains = this.filterChainProxy.getFilterChains();
		assertThat(chains).hasSize(2);
		MockHttpServletRequest integration = new MockHttpServletRequest("GET", "/api/integration/v1/anything/at/all");
		integration.setServletPath("/api/integration/v1/anything/at/all");
		MockHttpServletRequest main = new MockHttpServletRequest("GET", "/api/products");
		main.setServletPath("/api/products");

		assertThat(chains.get(0).matches(integration)).as("chaîne d'intégration en premier").isTrue();
		assertThat(chains.get(0).matches(main)).as("chaîne d'intégration limitée à son préfixe").isFalse();
		assertThat(chains.get(1).matches(main)).as("chaîne principale : tout le reste").isTrue();
	}

	@Test
	void noCorsHeaderIsEverSentOnIntegrationPaths() throws Exception {
		String origin = "http://localhost:4200";
		this.mvc.perform(options(CATALOG).header(HttpHeaders.ORIGIN, origin)
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
			.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
		this.mvc.perform(get(CATALOG).header(HttpHeaders.ORIGIN, origin)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + linkToken("catalog:read")))
			.andExpect(status().isOk())
			.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
			.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
		// Témoin : la chaîne principale garde son CORS pour le front.
		this.mvc.perform(options("/api/products").header(HttpHeaders.ORIGIN, origin)
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin));
	}

	@Test
	void noSessionIsCreated() throws Exception {
		var result = this.mvc.perform(get(CATALOG).header(HttpHeaders.AUTHORIZATION, "Bearer " + linkToken("catalog:read")))
			.andExpect(status().isOk())
			.andReturn();
		assertThat(result.getRequest().getSession(false)).isNull();
		assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
	}

	// --- Outils ---------------------------------------------------------------------------------------------

	private static void assertUnauthorized(ResultActions actions) throws Exception {
		actions.andExpect(status().isUnauthorized())
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\""))
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
			.andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString(MediaType.APPLICATION_PROBLEM_JSON_VALUE)))
			.andExpect(jsonPath("$.code").value("INVALID_TOKEN"))
			.andExpect(jsonPath("$.status").value(401));
	}

	private String linkToken(String... scopes) {
		return this.tokens.issue(LINK, "jcmb-test", Set.of(scopes)).value();
	}

	private byte[] testKey(String kid) {
		return Base64.getDecoder().decode(this.properties.token().keys().get(kid));
	}

	private static String forge(byte[] key, String kid, Instant issuedAt, Instant expiresAt, String scope) throws Exception {
		SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(new JOSEObjectType("at+jwt")).keyID(kid).build(),
				new JWTClaimsSet.Builder().issuer("geststock-integration").audience("geststock-integration")
					.subject(LINK.toString()).claim("typ", "integration").claim("client_id", "jcmb-test").claim("scope", scope)
					.issueTime(Date.from(issuedAt)).expirationTime(Date.from(expiresAt)).jwtID(UUID.randomUUID().toString())
					.build());
		jwt.sign(new MACSigner(key));
		return jwt.serialize();
	}

	private String userToken(String username) throws Exception {
		WithPermissionsSecurityContextFactory.runWithAllPermissions(() -> this.users.create(new CreateUserRequest(username, null,
				"Test", null, null, null, PASSWORD, List.of("ADMIN"), null, null, null)).id());
		String body = this.mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\": \"" + username + "\", \"password\": \"" + PASSWORD + "\"}"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return this.json.readTree(body).get("accessToken").asString();
	}

	/** Routes d'intégration de test, chargées seulement dans ce contexte. */
	@RestController
	@RequestMapping("/api/integration/v1/test-chain")
	static class ScopedTestController {

		@GetMapping("/catalog")
		@RequiresScope("catalog:read")
		Map<String, Object> catalog(@AuthenticationPrincipal LinkPrincipal link) {
			return Map.of("linkId", link.linkId().toString(), "clientId", link.clientId(),
					"scopes", link.scopes().stream().sorted().toList());
		}

		@GetMapping("/orders")
		@RequiresScope("orders:read")
		Map<String, Object> orders(@AuthenticationPrincipal LinkPrincipal link) {
			return Map.of("linkId", link.linkId().toString());
		}

		/** Sans portée déclarée : refusée par défaut (le garde-fou de build l'interdirait hors de ce test). */
		@GetMapping("/unscoped")
		Map<String, Object> unscoped() {
			return Map.of("served", true);
		}

	}

}
