package com.geststock.integration.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * Maintenance désactivée (valeur par défaut, G20-D2) : toute requête {@code /internal/**} reçoit la réponse d'une route
 * inconnue, avec ou sans secret, et aucun passage ne commence. La chaîne principale n'est jamais atteinte.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class MaintenanceDisabledIT {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private PlatformDb platform;

	@Test
	void disabledMaintenanceAnswersLikeAnUnknownRoute() throws Exception {
		long generation = this.platform.jdbc().sql("select generation from platform.integration_maintenance_lease")
			.query(Long.class).single();
		this.mvc.perform(post(MaintenanceRouteIT.ROUTE).header(HttpHeaders.AUTHORIZATION, "Bearer " + MaintenanceRouteIT.CURRENT))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
		this.mvc.perform(post(MaintenanceRouteIT.ROUTE)).andExpect(status().isNotFound());
		this.mvc.perform(get("/internal/autre")).andExpect(status().isNotFound());
		assertThat(this.platform.jdbc().sql("select generation from platform.integration_maintenance_lease")
			.query(Long.class).single()).isEqualTo(generation);
	}

}
