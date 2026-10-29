package com.geststock.integration.link;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.geststock.support.PostgresTestConfiguration;

/**
 * Liaisons désactivées ({@code geststock.integration.links.enabled=false}, défaut en production) : toute requête de
 * la chaîne d'intégration reçoit la même réponse 404 qu'une route inconnue, avec ou sans jeton, sans révéler
 * l'existence de l'intégration. La chaîne principale n'est pas affectée.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "geststock.integration.links.enabled=false")
@Import({ PostgresTestConfiguration.class, IntegrationSecurityChainIT.ScopedTestController.class })
class IntegrationSecurityChainDisabledIT {

	@Autowired
	private MockMvc mvc;

	@Test
	void everyIntegrationRequestIsNotFound() throws Exception {
		assertNotFound(this.mvc.perform(get("/api/integration/v1/test-chain/catalog")));
		assertNotFound(this.mvc.perform(get("/api/integration/v1/test-chain/catalog")
			.header(HttpHeaders.AUTHORIZATION, "Bearer eyJhbGciOiJIUzI1NiJ9.e30.x")));
		assertNotFound(this.mvc.perform(post("/api/integration/v1/oauth/token")));
		assertNotFound(this.mvc.perform(get("/api/integration/v1/n-importe-quoi")));
	}

	@Test
	void theResponseIsTheSameAsAnUnknownRoute() throws Exception {
		String integration = this.mvc.perform(get("/api/integration/v1/test-chain/catalog")).andReturn().getResponse()
			.getContentAsString();
		String unknown = this.mvc.perform(get("/api/public/route-inconnue")).andReturn().getResponse().getContentAsString();
		org.assertj.core.api.Assertions.assertThat(code(integration)).isEqualTo(code(unknown)).isEqualTo("RESOURCE_NOT_FOUND");
	}

	@Test
	void theMainChainIsUnaffected() throws Exception {
		this.mvc.perform(get("/api/products")).andExpect(status().isUnauthorized());
	}

	private static void assertNotFound(ResultActions actions) throws Exception {
		actions.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
			.andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
	}

	private static String code(String body) {
		int start = body.indexOf("\"code\":\"") + 8;
		return body.substring(start, body.indexOf('"', start));
	}

}
