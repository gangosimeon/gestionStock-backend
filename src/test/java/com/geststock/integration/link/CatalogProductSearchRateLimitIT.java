package com.geststock.integration.link;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.geststock.platform.PlatformDb;
import com.geststock.support.PostgresTestConfiguration;

/**
 * Limitation de débit de {@code GET /catalog/products/search} (lot 3, étape G23) : 60 recherches par minute
 * et par liaison. Comptées quelle que soit la validité des paramètres autres que {@code q} ; fenêtre fixe d'une
 * minute dans la base plateforme.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class CatalogProductSearchRateLimitIT {

	private static final String SEARCH = "/api/integration/v1/catalog/products/search";

	private static final Set<String> MATCH = Set.of("catalog:match");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private PlatformDb platform;

	@Autowired
	private LinkRegistry registry;

	@Autowired
	private IntegrationTokenService tokens;

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
	void theSixtyFirstSearchOfTheLinkIsRefusedAndAnotherLinkIsNotAffected() throws Exception {
		TestLinks.Client client = this.links.client();
		Link link = this.links.active(client, this.links.legacyCompany(), "shop-g23-rl-" + UUID.randomUUID(), MATCH);
		String token = this.tokens.issue(link.id(), client.clientId(), MATCH).value();

		TestLinks.Client otherClient = this.links.client();
		Link otherLink = this.links.active(otherClient, this.links.legacyCompany(),
				"shop-g23-rl-other-" + UUID.randomUUID(), MATCH);
		String other = this.tokens.issue(otherLink.id(), otherClient.clientId(), MATCH).value();

		awaitFreshWindow();

		for (int i = 0; i < 60; i++) {
			this.mvc.perform(get(SEARCH).param("q", "ab")
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
					.andExpect(status().isOk());
		}
		this.mvc.perform(get(SEARCH).param("q", "ab")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists(HttpHeaders.RETRY_AFTER))
				.andExpect(jsonPath("$.code").value("RATE_LIMITED"));

		// L'autre liaison n'est pas affectée.
		this.mvc.perform(get(SEARCH).param("q", "ab")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + other))
				.andExpect(status().isOk());
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
				throw new IllegalStateException(ex);
			}
		}
	}

}
