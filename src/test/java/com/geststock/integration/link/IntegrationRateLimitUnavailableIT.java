package com.geststock.integration.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.geststock.platform.PlatformDb;
import com.geststock.support.PostgresTestConfiguration;

/**
 * Compteurs de débit inaccessibles (v13-D4) : la requête est refusée en {@code 503 RATE_LIMIT_UNAVAILABLE}, jamais
 * laissée passer, même avec un client et une demande valides. Le reste de la base plateforme fonctionne : seul le
 * limiteur reçoit une source de données en panne.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({ PostgresTestConfiguration.class, IntegrationRateLimitUnavailableIT.BrokenCounters.class })
class IntegrationRateLimitUnavailableIT {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private PlatformDb platform;

	@Autowired
	private LinkRegistry registry;

	private TestLinks links;

	@BeforeEach
	void setUp() {
		this.links = new TestLinks(this.platform, this.registry);
	}

	@AfterEach
	void tearDown() {
		this.links.cleanUp();
	}

	@Test
	void unavailableCountersRefuseTheRequestAndServeNothing() throws Exception {
		String secret = "secret-g9-indisponible-" + UUID.randomUUID();
		TestLinks.Client client = this.links.client(secret, null, Set.of("catalog:read"));
		String authorization = "Basic "
				+ Base64.getEncoder().encodeToString((client.clientId() + ":" + secret).getBytes(StandardCharsets.UTF_8));

		this.mvc.perform(post("/api/integration/v1/oauth/par").contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.header(HttpHeaders.AUTHORIZATION, authorization)
			.param("response_type", "code")
			.param("redirect_uri", TestLinks.REDIRECT_URI)
			.param("code_challenge", "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
			.param("code_challenge_method", "S256")
			.param("scope", "catalog:read")
			.param("state", "s".repeat(40))
			.param("shop_id", "shop-g9-503")
			.param("shop_name", "Boutique indisponible"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
			.andExpect(jsonPath("$.code").value("RATE_LIMIT_UNAVAILABLE"));
		assertThat(this.platform.jdbc().sql("select count(*) from platform.integration_authorization_request where client_id = :c")
			.param("c", client.id()).query(Integer.class).single()).as("aucune demande enregistrée").isZero();

		ActivatedLink link = this.registry.activate(this.links.pending(client, this.links.legacyCompany(), "shop-g9-503-b",
				Set.of("catalog:read")).id());
		this.mvc.perform(post("/api/integration/v1/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.header(HttpHeaders.AUTHORIZATION, authorization)
			.param("grant_type", "client_credentials")
			.param("link_id", link.link().id().toString())
			.param("link_secret", link.secret()))
			.andExpect(status().isServiceUnavailable())
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
			.andExpect(jsonPath("$.code").value("RATE_LIMIT_UNAVAILABLE"))
			.andExpect(jsonPath("$.access_token").doesNotExist());
	}

	/** Limiteur branché sur une base qui refuse toute connexion. */
	@TestConfiguration(proxyBeanMethods = false)
	static class BrokenCounters {

		@Bean
		@Primary
		RateLimiter brokenRateLimiter() {
			return new RateLimiter(JdbcClient.create(new AbstractDataSource() {

				@Override
				public Connection getConnection() throws SQLException {
					throw new SQLException("Compteurs de débit inaccessibles (test)");
				}

				@Override
				public Connection getConnection(String username, String password) throws SQLException {
					return getConnection();
				}

			}));
		}

	}

}
