package com.geststock.support;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Base PostgreSQL des tests d'intégration.
 *
 * <p>Propriété {@code geststock.test.testcontainers} (variable d'environnement
 * {@code GESTSTOCK_TEST_TESTCONTAINERS}) :
 * <ul>
 * <li>{@code auto} (défaut) : conteneur Testcontainers si Docker est disponible, sinon base
 * externe définie dans {@code application-test.yml} ;</li>
 * <li>{@code true} : conteneur obligatoire ;</li>
 * <li>{@code false} : toujours la base externe.</li>
 * </ul>
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfiguration {

	static final String POSTGRES_IMAGE = "postgres:17-alpine";

	@Bean
	@ServiceConnection
	@Conditional(UseTestcontainersCondition.class)
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE));
	}

	static class UseTestcontainersCondition implements Condition {

		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
			String mode = context.getEnvironment().getProperty("geststock.test.testcontainers", "auto");
			return switch (mode.trim().toLowerCase()) {
				case "true" -> true;
				case "false" -> false;
				default -> DockerClientFactory.instance().isDockerAvailable();
			};
		}

	}

}
