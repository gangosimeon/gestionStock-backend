package com.geststock.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.geststock.integration.IntegrationProperties;
import com.geststock.integration.PageTokenProperties;
import com.geststock.security.GestStockSecurityProperties;

/**
 * Garde-fou du profil prod pour l'IP réelle (lot 2, étape G9 ; v14-D8) : liaisons activées sans en-tête d'IP configuré
 * ⇒ démarrage refusé (la limitation par IP de {@code /oauth/token} n'aurait sinon que {@code getRemoteAddr()}, réécrit
 * depuis {@code X-Forwarded-For} par {@code forward-headers-strategy: framework}). {@code X-Forwarded-For} et
 * {@code Forwarded} ne sont jamais acceptés comme en-tête d'IP. Liaisons désactivées : aucun en-tête exigé.
 */
class ProductionClientIpSafetyTest {

	private static final String LINK_KEY = Base64.getEncoder()
		.encodeToString("prod-like-link-token-key-for-tests-only-0001".getBytes(StandardCharsets.UTF_8));

	private final ApplicationContextRunner production = new ApplicationContextRunner()
		.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
		.withUserConfiguration(SafetyCheckConfiguration.class)
		.withPropertyValues("geststock.cors.allowed-origins=https://geststock.example.com",
				"geststock.platform.datasource.url=jdbc:postgresql://db/geststock_platform",
				"geststock.platform.provisioner.url=jdbc:postgresql://db/postgres",
				"geststock.platform.credentials-key=dGVzdC1vbmx5LWtleS0zMi1ieXRlcy1sb25nLTAwMDA=",
				"geststock.security.jwt.secret=prod-like-user-jwt-secret-for-tests-only-0001");

	private final ApplicationContextRunner enabledLinks = this.production.withPropertyValues(
			"geststock.integration.links.enabled=true", "geststock.integration.links.token.current-kid=prod-1",
			"geststock.integration.links.token.keys.prod-1=" + LINK_KEY);

	@Test
	void enabledLinksWithoutAClientIpHeaderRefuseToStart() {
		this.enabledLinks.run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.hasMessageContaining("Configuration de production refusée")
				.hasMessageContaining("geststock.integration.client-ip-header");
		});
		this.enabledLinks.withPropertyValues("geststock.integration.client-ip-header= ").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("geststock.integration.client-ip-header");
		});
	}

	@Test
	void forwardedHeadersAreNeverAcceptedAsTheClientIpHeader() {
		for (String header : new String[] { "X-Forwarded-For", "x-forwarded-for", "Forwarded" }) {
			this.enabledLinks.withPropertyValues("geststock.integration.client-ip-header=" + header).run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause()
					.hasMessageContaining("geststock.integration.client-ip-header")
					.hasMessageContaining(header);
			});
		}
	}

	@Test
	void aConfiguredHeaderOrDisabledLinksStart() {
		this.enabledLinks.withPropertyValues("geststock.integration.client-ip-header=X-Real-IP")
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
