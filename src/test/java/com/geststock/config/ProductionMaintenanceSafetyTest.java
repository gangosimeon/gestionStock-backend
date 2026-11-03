package com.geststock.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.geststock.integration.IntegrationProperties;
import com.geststock.integration.PageTokenProperties;
import com.geststock.security.GestStockSecurityProperties;

/**
 * Garde-fou du profil prod pour la maintenance de l'intégration (G20-D1, G20-D2) : maintenance activée sans empreinte du
 * secret du cron, ou avec une empreinte qui n'est pas un SHA-256 hexadécimal ⇒ démarrage refusé, sans jamais citer la
 * valeur. Maintenance désactivée (par défaut) : aucune empreinte exigée.
 */
class ProductionMaintenanceSafetyTest {

	private static final String SHA256 = "aa30cfbcb57ceb7df60ec3ed045c6fded70fb3635ca26f72edd1018ecd4bc112";

	private final ApplicationContextRunner production = new ApplicationContextRunner()
		.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
		.withUserConfiguration(SafetyCheckConfiguration.class)
		.withPropertyValues("geststock.cors.allowed-origins=https://geststock.example.com",
				"geststock.platform.datasource.url=jdbc:postgresql://db/geststock_platform",
				"geststock.platform.provisioner.url=jdbc:postgresql://db/postgres",
				"geststock.platform.credentials-key=dGVzdC1vbmx5LWtleS0zMi1ieXRlcy1sb25nLTAwMDA=",
				"geststock.security.jwt.secret=prod-like-user-jwt-secret-for-tests-only-0001");

	@Test
	void enabledMaintenanceWithoutAFingerprintRefusesToStart() {
		this.production.withPropertyValues("geststock.integration.maintenance.enabled=true").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.hasMessageContaining("Configuration de production refusée")
				.hasMessageContaining("GESTSTOCK_MAINTENANCE_TOKEN_SHA256");
		});
		this.production.withPropertyValues("geststock.integration.maintenance.enabled=true",
				"geststock.integration.maintenance.token-sha256= ").run(context -> assertThat(context).hasFailed());
	}

	@Test
	void aFingerprintThatIsNotASha256RefusesToStartWithoutQuotingIt() {
		for (String value : new String[] { "secret-du-cron-colle-par-erreur-0123456789", SHA256.substring(1), SHA256 + "0" }) {
			this.production.withPropertyValues("geststock.integration.maintenance.enabled=true",
					"geststock.integration.maintenance.token-sha256=" + value).run(context -> {
						assertThat(context).hasFailed();
						assertThat(context.getStartupFailure()).rootCause()
							.hasMessageContaining("token-sha256")
							.hasMessageNotContaining(value);
					});
		}
		this.production.withPropertyValues("geststock.integration.maintenance.enabled=true",
				"geststock.integration.maintenance.token-sha256=" + SHA256,
				"geststock.integration.maintenance.previous-token-sha256=pas-une-empreinte").run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).rootCause()
						.hasMessageContaining("previous-token-sha256")
						.hasMessageNotContaining("pas-une-empreinte");
				});
	}

	@Test
	void aValidFingerprintOrDisabledMaintenanceStart() {
		this.production.withPropertyValues("geststock.integration.maintenance.enabled=true",
				"geststock.integration.maintenance.token-sha256=" + SHA256.toUpperCase())
			.run(context -> assertThat(context).hasNotFailed());
		this.production.run(context -> assertThat(context).hasNotFailed());
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties({ CorsProperties.class, GestStockSecurityProperties.class, IntegrationProperties.class,
			PageTokenProperties.class })
	@Import(ProductionSafetyCheck.class)
	static class SafetyCheckConfiguration {

	}

}
