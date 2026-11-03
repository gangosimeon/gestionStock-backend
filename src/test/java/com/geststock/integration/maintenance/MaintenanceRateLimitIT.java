package com.geststock.integration.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
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

import com.geststock.platform.PlatformDb;
import com.geststock.support.PostgresTestConfiguration;

/**
 * Limite des appels authentifiés de la maintenance (G20-D14) : compteur global en base, consommé seulement après un
 * secret valide ; au-delà, {@code 429 RATE_LIMITED} avec {@code Retry-After}, sans passage ; un secret faux reste
 * {@code 401} et ne consomme rien.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@TestPropertySource(properties = { "geststock.integration.maintenance.enabled=true",
		"geststock.integration.maintenance.token-sha256=" + MaintenanceRouteIT.CURRENT_SHA256,
		"geststock.integration.maintenance.calls-per-window=2" })
class MaintenanceRateLimitIT {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private PlatformDb platform;

	@Test
	void beyondTheLimitAuthenticatedCallsAreRefusedAndFailuresConsumeNothing() throws Exception {
		awaitFreshWindow();
		this.platform.jdbc().sql("delete from platform.integration_rate_limit where counter_key = :key")
			.param("key", MaintenanceController.CALLS_COUNTER).update();
		for (int i = 0; i < 5; i++) {
			this.mvc.perform(post(MaintenanceRouteIT.ROUTE).header(HttpHeaders.AUTHORIZATION, "Bearer faux-" + i))
				.andExpect(status().isUnauthorized());
		}
		long generation = generation();
		call().andExpect(status().isOk());
		call().andExpect(status().isOk());
		call().andExpect(status().isTooManyRequests())
			.andExpect(header().exists(HttpHeaders.RETRY_AFTER))
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
			.andExpect(jsonPath("$.code").value("RATE_LIMITED"))
			.andExpect(jsonPath("$.path").value(MaintenanceRouteIT.ROUTE));
		assertThat(generation()).as("deux passages seulement").isEqualTo(generation + 2);
		this.mvc.perform(post(MaintenanceRouteIT.ROUTE).header(HttpHeaders.AUTHORIZATION, "Bearer faux"))
			.andExpect(status().isUnauthorized());
	}

	private ResultActions call() throws Exception {
		return this.mvc.perform(post(MaintenanceRouteIT.ROUTE).header(HttpHeaders.AUTHORIZATION,
				"Bearer " + MaintenanceRouteIT.CURRENT));
	}

	private long generation() {
		return this.platform.jdbc().sql("select generation from platform.integration_maintenance_lease").query(Long.class).single();
	}

	/** Fenêtre fixe d'une minute : attendre la suivante s'il reste moins de 20 secondes (horloge de PostgreSQL). */
	private void awaitFreshWindow() throws InterruptedException {
		double remaining = this.platform.jdbc().sql("select 60 - mod(extract(epoch from clock_timestamp()), 60)::float8")
			.query(Double.class).single();
		if (remaining < 20) {
			Thread.sleep((long) (remaining * 1000) + 200);
		}
	}

}
