package com.geststock.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.geststock.integration.IntegrationProperties;
import com.geststock.integration.PageTokenProperties;
import com.geststock.integration.link.IntegrationLinkProperties;
import com.geststock.security.GestStockSecurityProperties;

/**
 * Garde-fou du profil prod pour les jetons de liaison (lot 2, étape G5) : liaisons activées et clé courante
 * absente, trop courte ou identique au secret JWT des utilisateurs ⇒ démarrage refusé, avec un message qui ne
 * révèle aucune clé. Liaisons désactivées (défaut) : aucune clé exigée.
 */
@ExtendWith(OutputCaptureExtension.class)
class ProductionLinkTokenSafetyTest {

	private static final String USER_SECRET = "prod-like-user-jwt-secret-for-tests-only-0001";

	private static final String LINK_KEY = base64("prod-like-link-token-key-for-tests-only-0001");

	private final ApplicationContextRunner production = new ApplicationContextRunner()
		.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
		.withUserConfiguration(SafetyCheckConfiguration.class)
		.withPropertyValues("geststock.cors.allowed-origins=https://geststock.example.com",
				"geststock.platform.datasource.url=jdbc:postgresql://db/geststock_platform",
				"geststock.platform.provisioner.url=jdbc:postgresql://db/postgres",
				"geststock.platform.credentials-key=dGVzdC1vbmx5LWtleS0zMi1ieXRlcy1sb25nLTAwMDA=",
				"geststock.security.jwt.secret=" + USER_SECRET);

	@Test
	void enabledLinksWithoutACurrentKeyRefuseToStart() {
		this.production.withPropertyValues("geststock.integration.links.enabled=true").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.hasMessageContaining("Configuration de production refusée")
				.hasMessageContaining("geststock.integration.links.token.current-kid");
		});
	}

	@Test
	void aShortCurrentKeyRefusesToStartWithoutRevealingIt() {
		String shortKey = base64("courte");
		this.production.withPropertyValues("geststock.integration.links.enabled=true",
				"geststock.integration.links.token.current-kid=prod-1",
				"geststock.integration.links.token.keys.prod-1=" + shortKey).run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).rootCause()
						.hasMessageContaining("32 octets")
						.hasMessageNotContaining(shortKey);
				});
	}

	@Test
	void aCurrentKeyEqualToTheUserJwtSecretRefusesToStartWithoutRevealingEither(CapturedOutput output) {
		String sameAsUser = base64(USER_SECRET);
		this.production.withPropertyValues("geststock.integration.links.enabled=true",
				"geststock.integration.links.token.current-kid=prod-1",
				"geststock.integration.links.token.keys.prod-1=" + sameAsUser).run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).rootCause()
						.hasMessageContaining("identique au secret JWT des utilisateurs")
						.hasMessageNotContaining(sameAsUser)
						.hasMessageNotContaining(USER_SECRET);
				});
		assertThat(output.getAll()).doesNotContain(sameAsUser, USER_SECRET);
	}

	@Test
	void aKeyWhoseTextIsTheUserSecretIsAlsoRefused() {
		// Secret utilisateur qui a lui-même la forme d'une valeur Base64, recopié tel quel comme clé de liaison.
		IntegrationLinkProperties links = new IntegrationLinkProperties(true,
				new IntegrationLinkProperties.Token("prod-1", Map.of("prod-1", LINK_KEY), Duration.ofSeconds(30)));

		assertThat(ProductionSafetyCheck.linkProblems(links, LINK_KEY)).singleElement().asString()
			.contains("« prod-1 »", "identique au secret JWT des utilisateurs").doesNotContain(LINK_KEY);
		assertThat(ProductionSafetyCheck.linkProblems(links, USER_SECRET)).isEmpty();
		assertThat(ProductionSafetyCheck.linkProblems(new IntegrationLinkProperties(false,
				new IntegrationLinkProperties.Token(null, Map.of(), Duration.ofSeconds(30))), USER_SECRET)).isEmpty();
	}

	@Test
	void aDistinctValidKeyOrDisabledLinksStart(CapturedOutput output) {
		this.production.withPropertyValues("geststock.integration.links.enabled=true",
				"geststock.integration.links.token.current-kid=prod-1",
				"geststock.integration.links.token.keys.prod-1=" + LINK_KEY,
				// Exigé depuis G9 (v14-D8), vérifié par ProductionClientIpSafetyTest.
				"geststock.integration.client-ip-header=X-Real-IP")
			.run(context -> assertThat(context).hasNotFailed());
		this.production.run(context -> assertThat(context).hasNotFailed());
		assertThat(output.getAll()).doesNotContain(LINK_KEY, USER_SECRET);
	}

	private static String base64(String raw) {
		return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties({ CorsProperties.class, GestStockSecurityProperties.class, IntegrationProperties.class,
			PageTokenProperties.class })
	@Import(ProductionSafetyCheck.class)
	static class SafetyCheckConfiguration {

	}

}
