package com.geststock.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.geststock.support.PostgresTestConfiguration;
import com.geststock.user.UserAccountService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contournement du front : un utilisateur authentifié (vrai jeton signé par le serveur) mais
 * sans aucun rôle appelle directement <b>chaque</b> route de l'API, comme le ferait un script
 * hors d'Angular. Aucune ne doit le servir.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@Transactional
class DirectApiAccessIT {

	/** Routes publiques (connexion, santé) : hors périmètre. */
	private static final Set<String> PUBLIC = Set.of("POST /api/auth/login", "POST /api/auth/refresh",
			"POST /api/auth/logout", "POST /api/auth/forgot-password", "GET /api/health");

	/** Routes ouvertes à tout utilisateur connecté (son propre profil, son propre mot de passe). */
	private static final Set<String> SELF = Set.of("GET /api/auth/me", "POST /api/auth/change-password");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private UserAccountService accounts;

	@Autowired
	private JwtTokenService tokens;

	@Autowired
	private org.springframework.security.oauth2.jwt.JwtEncoder encoder;

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping mappings;

	@Test
	void aTokenWithoutExpiryIsRejected() throws Exception {
		UUID user = this.jdbc.queryForObject("""
				insert into app_user (username, last_name, must_change_password) values ('sans_exp', 'Sans exp', false)
				returning id""", UUID.class);
		// Signé avec la vraie clé, mais sans date d'expiration : un jeton « éternel ».
		org.springframework.security.oauth2.jwt.JwtClaimsSet claims = org.springframework.security.oauth2.jwt.JwtClaimsSet
			.builder()
			.issuer("geststock")
			.subject(user.toString())
			.issuedAt(java.time.Instant.now())
			.build();
		String token = this.encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
				org.springframework.security.oauth2.jwt.JwsHeader
					.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(),
				claims)).getTokenValue();

		int status = this.mvc.perform(MockMvcRequestBuilders.get("/api/auth/me").header("Authorization", "Bearer " + token))
			.andReturn().getResponse().getStatus();

		assertThat(status).as("jeton sans exp").isEqualTo(401);
	}

	@Test
	void aUserWithoutPermissionCannotUseAnyRouteDirectly() throws Exception {
		UUID nobody = this.jdbc.queryForObject("""
				insert into app_user (username, last_name, must_change_password) values ('sans_role_direct', 'Sans rôle', false)
				returning id""", UUID.class);
		String token = this.tokens.issue(this.accounts.findById(nobody).orElseThrow()).value();

		Map<String, Integer> statuses = new TreeMap<>();
		for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : this.mappings.getHandlerMethods().entrySet()) {
			for (String pattern : entry.getKey().getPatternValues()) {
				if (!pattern.startsWith("/api/")) {
					continue;
				}
				for (var method : entry.getKey().getMethodsCondition().getMethods()) {
					String key = method.name() + " " + pattern;
					if (PUBLIC.contains(key)) {
						continue;
					}
					String path = pattern.replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
					MockHttpServletRequestBuilder request = MockMvcRequestBuilders
						.request(HttpMethod.valueOf(method.name()), path)
						.header("Authorization", "Bearer " + token);
					if (method.name().equals("POST") || method.name().equals("PUT") || method.name().equals("PATCH")) {
						request.contentType(MediaType.APPLICATION_JSON).content("{}");
					}
					statuses.put(key, this.mvc.perform(request).andReturn().getResponse().getStatus());
				}
			}
		}

		List<String> served = new ArrayList<>();
		statuses.forEach((route, status) -> {
			if (SELF.contains(route)) {
				return;
			}
			if (status != 403) {
				served.add(route + " → " + status);
			}
		});
		assertThat(statuses).as("routes appelées").hasSizeGreaterThan(120);
		assertThat(served).as("routes qui n'opposent pas 403 à un utilisateur sans permission").isEmpty();
	}

}
