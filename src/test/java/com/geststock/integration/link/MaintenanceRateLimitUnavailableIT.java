package com.geststock.integration.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.geststock.platform.PlatformDb;
import com.geststock.support.PostgresTestConfiguration;

/**
 * Compteur des appels de maintenance inaccessible (v13-D4, G20-D14) : {@code 503 RATE_LIMIT_UNAVAILABLE}, jamais de
 * laisser-passer, aucun passage commencé. Seul le limiteur reçoit une source de données en panne.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({ PostgresTestConfiguration.class, MaintenanceRateLimitUnavailableIT.BrokenCounters.class })
@TestPropertySource(properties = { "geststock.integration.maintenance.enabled=true",
		"geststock.integration.maintenance.token-sha256=" + MaintenanceRateLimitUnavailableIT.CURRENT_SHA256 })
class MaintenanceRateLimitUnavailableIT {

	private static final String ROUTE = "/internal/integration/maintenance";

	/** Secret public de test (le même que MaintenanceRouteIT), sans valeur hors des tests automatisés. */
	private static final String CURRENT = "test-maintenance-token-current-not-a-secret-0123456789";

	static final String CURRENT_SHA256 = "aa30cfbcb57ceb7df60ec3ed045c6fded70fb3635ca26f72edd1018ecd4bc112";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private PlatformDb platform;

	@Test
	void unavailableCountersRefuseThePass() throws Exception {
		long generation = generation();
		this.mvc.perform(post(ROUTE).header(HttpHeaders.AUTHORIZATION, "Bearer " + CURRENT))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("RATE_LIMIT_UNAVAILABLE"));
		assertThat(generation()).as("aucun passage commencé").isEqualTo(generation);
	}

	private long generation() {
		return this.platform.jdbc().sql("select generation from platform.integration_maintenance_lease").query(Long.class).single();
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
