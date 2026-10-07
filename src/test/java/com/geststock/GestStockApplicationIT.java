package com.geststock;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.geststock.support.PostgresTestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasKey;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Démarrage complet de l'application sur PostgreSQL : contexte Spring, migrations Flyway,
 * validation du schéma par Hibernate, routes publiques et protégées, documentation OpenAPI.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class GestStockApplicationIT {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void flywayAppliedAllMigrationsBeforeHibernateValidatedTheSchema() throws Exception {
		// Le contexte n'a pu démarrer que si Hibernate (ddl-auto=validate) a trouvé un schéma
		// conforme aux entités : ce test vérifie en plus que Flyway en est bien l'auteur.
		Integer applied = this.jdbcTemplate.queryForObject(
				"select count(*) from flyway_schema_history where version is not null and success", Integer.class);
		int migrationFiles = new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
			.getResources("classpath:db/migration/V*.sql").length;
		assertThat(applied).isEqualTo(migrationFiles);
	}

	@Test
	void healthIsUpAndPublic() throws Exception {
		this.mvc.perform(get("/api/health"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"))
			.andExpect(jsonPath("$.components.database").value("UP"));
	}

	@Test
	void protectedRoutesRequireAuthentication() throws Exception {
		this.mvc.perform(get("/api/products"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void openApiDocumentIsPublishedInTestProfile() throws Exception {
		this.mvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.info.title").value("GestStock API"))
			.andExpect(jsonPath("$.paths", hasKey("/api/health")))
			.andExpect(jsonPath("$.components.securitySchemes", hasKey("bearerAuth")));
	}

	@Test
	void actuatorHealthIsAvailableForProbes() throws Exception {
		this.mvc.perform(get("/actuator/health"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"));
	}

}
