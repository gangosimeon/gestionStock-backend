package com.geststock.config;

import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.geststock.integration.IntegrationProperties;
import com.geststock.integration.PageTokenProperties;
import com.geststock.security.GestStockSecurityProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garde-fou du profil prod pour l'intégration boutique : publication activée sans clé courante valide de
 * signature des {@code pageToken} (32 octets au moins) ⇒ démarrage refusé, avec un message explicite qui ne
 * révèle aucune clé.
 */
class ProductionIntegrationSafetyTest {

	private static final String VALID_KEY = "cHJvZC1saWtlLXBhZ2UtdG9rZW4ta2V5LWZvci10ZXN0cy1vbmx5";

	private final ApplicationContextRunner production = new ApplicationContextRunner()
		.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
		.withUserConfiguration(SafetyCheckConfiguration.class)
		.withPropertyValues("geststock.cors.allowed-origins=https://geststock.example.com",
				"geststock.platform.datasource.url=jdbc:postgresql://db/geststock_platform",
				"geststock.platform.provisioner.url=jdbc:postgresql://db/postgres",
				"geststock.platform.credentials-key=dGVzdC1vbmx5LWtleS0zMi1ieXRlcy1sb25nLTAwMDA=");

	@Test
	void publishingWithoutACurrentKeyRefusesToStart() {
		this.production.withPropertyValues("geststock.integration.publish.enabled=true").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.hasMessageContaining("Configuration de production refusée")
				.hasMessageContaining("geststock.integration.page-token.current-kid");
		});
	}

	@Test
	void aShortCurrentKeyRefusesToStartWithoutRevealingIt() {
		String shortKey = "Y291cnRl";
		this.production.withPropertyValues("geststock.integration.publish.enabled=true",
				"geststock.integration.page-token.current-kid=prod-1",
				"geststock.integration.page-token.keys.prod-1=" + shortKey).run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).rootCause()
						.hasMessageContaining("32 octets")
						.hasMessageNotContaining(shortKey);
				});
	}

	@Test
	void aValidCurrentKeyOrADisabledPublicationStarts() {
		this.production.withPropertyValues("geststock.integration.publish.enabled=true",
				"geststock.integration.page-token.current-kid=prod-1",
				"geststock.integration.page-token.keys.prod-1=" + VALID_KEY)
			.run(context -> assertThat(context).hasNotFailed());
		this.production.run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	void theProblemsAreListedWithoutAnyKey() {
		assertThat(ProductionSafetyCheck.integrationProblems(false, new PageTokenProperties(null, Map.of()))).isEmpty();
		assertThat(ProductionSafetyCheck.integrationProblems(true, new PageTokenProperties(null, Map.of())))
			.singleElement().asString().contains("current-kid");
		assertThat(ProductionSafetyCheck.integrationProblems(true, new PageTokenProperties("prod-1", Map.of())))
			.singleElement().asString().contains("prod-1");
		assertThat(ProductionSafetyCheck.integrationProblems(true,
				new PageTokenProperties("prod-1", Map.of("prod-1", VALID_KEY)))).isEmpty();
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties({ CorsProperties.class, GestStockSecurityProperties.class, IntegrationProperties.class,
			PageTokenProperties.class })
	@Import(ProductionSafetyCheck.class)
	static class SafetyCheckConfiguration {

	}

}
