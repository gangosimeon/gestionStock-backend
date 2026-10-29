package com.geststock.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.geststock.integration.IntegrationProperties;
import com.geststock.integration.PageTokenProperties;
import com.geststock.security.GestStockSecurityProperties;

/**
 * Garde-fous du profil {@code prod} : l'application refuse de démarrer sur une configuration
 * dangereuse plutôt que de tourner silencieusement en mode non sécurisé.
 *
 * <ul>
 * <li>CORS : au moins une origine, jamais {@code *}, uniquement en {@code https://} (sauf
 * {@code geststock.production.allow-http-origins=true}, réservé à une recette locale) ;</li>
 * <li>mot de passe de l'administrateur initial : 12 caractères au moins s'il est fourni ;</li>
 * <li>Flyway : {@code clean} interdit (effacement complet du schéma) ;</li>
 * <li>intégration boutique : publication activée ⇒ clé courante valide de signature des {@code pageToken}
 * d'instantané (32 octets au moins), sans jamais afficher la clé.</li>
 * </ul>
 * Le secret JWT (32 octets minimum) est déjà vérifié par {@code JwtConfig}.
 */
@Component
@Profile("prod")
class ProductionSafetyCheck implements InitializingBean {

	private static final Logger log = LoggerFactory.getLogger(ProductionSafetyCheck.class);

	static final int MIN_ADMIN_PASSWORD_LENGTH = 12;

	private final CorsProperties cors;

	private final GestStockSecurityProperties security;

	private final Environment environment;

	private final IntegrationProperties integration;

	private final PageTokenProperties pageToken;

	ProductionSafetyCheck(CorsProperties cors, GestStockSecurityProperties security, Environment environment,
			IntegrationProperties integration, PageTokenProperties pageToken) {
		this.cors = cors;
		this.security = security;
		this.environment = environment;
		this.integration = integration;
		this.pageToken = pageToken;
	}

	@Override
	public void afterPropertiesSet() {
		List<String> problems = new ArrayList<>(problems(this.cors.allowedOrigins(),
				this.environment.getProperty("geststock.production.allow-http-origins", Boolean.class, false),
				this.security.bootstrapAdmin().password(),
				this.environment.getProperty("spring.flyway.clean-disabled", Boolean.class, true)));
		problems.addAll(tenancyProblems(this.environment.getProperty("geststock.tenancy.default-company"),
				this.environment.getProperty("geststock.platform.datasource.url"),
				this.environment.getProperty("geststock.platform.provisioner.url"),
				this.environment.getProperty("geststock.platform.credentials-key"),
				this.environment.getProperty("geststock.platform.bootstrap-admin.password")));
		problems.addAll(integrationProblems(this.integration.publish().enabled(), this.pageToken));
		if (!problems.isEmpty()) {
			throw new IllegalStateException("Configuration de production refusée :\n - " + String.join("\n - ", problems));
		}
		log.info("Contrôles de production passés : CORS {} ; secret JWT vérifié ; Flyway clean interdit.",
				this.cors.allowedOrigins());
	}

	/**
	 * Multi-entreprises : aucune entreprise par défaut (jamais de base visée sans authentification), base
	 * plateforme séparée, compte de provisionnement et clé de chiffrement définis, mot de passe du premier
	 * Super Admin robuste s'il est fourni.
	 */
	static List<String> tenancyProblems(String defaultCompany, String platformUrl, String provisionerUrl,
			String credentialsKey, String platformAdminPassword) {
		List<String> problems = new ArrayList<>();
		if (defaultCompany != null && !defaultCompany.isBlank()) {
			problems.add("GESTSTOCK_DEFAULT_COMPANY est interdit en production (dev/test uniquement).");
		}
		if (platformUrl == null || platformUrl.isBlank()) {
			problems.add("GESTSTOCK_PLATFORM_DB_URL est vide : la base plateforme doit être une base distincte.");
		}
		if (provisionerUrl == null || provisionerUrl.isBlank()) {
			problems.add("GESTSTOCK_PROVISIONER_DB_URL est vide : compte de provisionnement requis.");
		}
		if (credentialsKey == null || credentialsKey.isBlank()) {
			problems.add("GESTSTOCK_TENANT_CREDENTIALS_KEY est vide : clé AES-256 (Base64, 32 octets) requise.");
		}
		if (platformAdminPassword != null && !platformAdminPassword.isBlank()
				&& platformAdminPassword.length() < MIN_ADMIN_PASSWORD_LENGTH) {
			problems.add("GESTSTOCK_PLATFORM_ADMIN_PASSWORD : " + MIN_ADMIN_PASSWORD_LENGTH + " caractères au moins.");
		}
		return problems;
	}

	/**
	 * Intégration boutique : si la publication est activée, l'instantané doit pouvoir signer ses {@code pageToken}
	 * (clé courante valide, 32 octets au moins). Les messages nomment le {@code kid}, jamais la clé.
	 */
	static List<String> integrationProblems(boolean publishEnabled, PageTokenProperties pageToken) {
		return publishEnabled ? pageToken.problems() : List.of();
	}

	/** Problèmes bloquants (liste vide : configuration acceptée). */
	static List<String> problems(List<String> origins, boolean allowHttpOrigins, String adminPassword,
			boolean flywayCleanDisabled) {
		List<String> problems = new ArrayList<>();
		if (origins == null || origins.isEmpty()) {
			problems.add("GESTSTOCK_CORS_ALLOWED_ORIGINS est vide : indiquer l'URL publique du front.");
		}
		else {
			for (String origin : origins) {
				String value = origin.trim().toLowerCase(Locale.ROOT);
				if (value.contains("*")) {
					problems.add("Origine CORS « " + origin + " » : le joker * est interdit en production.");
				}
				else if (!value.startsWith("https://") && !(allowHttpOrigins && value.startsWith("http://"))) {
					problems.add("Origine CORS « " + origin + " » : https:// obligatoire en production.");
				}
			}
		}
		if (adminPassword != null && !adminPassword.isBlank() && adminPassword.length() < MIN_ADMIN_PASSWORD_LENGTH) {
			problems.add("GESTSTOCK_ADMIN_PASSWORD : " + MIN_ADMIN_PASSWORD_LENGTH + " caractères au moins.");
		}
		if (!flywayCleanDisabled) {
			problems.add("spring.flyway.clean-disabled doit rester à true (clean efface tout le schéma).");
		}
		return problems;
	}

}
