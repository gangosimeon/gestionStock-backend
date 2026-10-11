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

import com.geststock.security.GestStockSecurityProperties;

/**
 * Garde-fous du profil {@code prod} : l'application refuse de démarrer sur une configuration
 * dangereuse plutôt que de tourner silencieusement en mode non sécurisé.
 *
 * <ul>
 * <li>CORS : au moins une origine, jamais {@code *}, uniquement en {@code https://} (sauf
 * {@code geststock.production.allow-http-origins=true}, réservé à une recette locale) ;</li>
 * <li>mot de passe de l'administrateur initial : 12 caractères au moins s'il est fourni ;</li>
 * <li>Flyway : {@code clean} interdit (effacement complet du schéma).</li>
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

	ProductionSafetyCheck(CorsProperties cors, GestStockSecurityProperties security, Environment environment) {
		this.cors = cors;
		this.security = security;
		this.environment = environment;
	}

	@Override
	public void afterPropertiesSet() {
		List<String> problems = problems(this.cors.allowedOrigins(),
				this.environment.getProperty("geststock.production.allow-http-origins", Boolean.class, false),
				this.security.bootstrapAdmin().password(),
				this.environment.getProperty("spring.flyway.clean-disabled", Boolean.class, true));
		if (!problems.isEmpty()) {
			throw new IllegalStateException("Configuration de production refusée :\n - " + String.join("\n - ", problems));
		}
		log.info("Contrôles de production passés : CORS {} ; secret JWT vérifié ; Flyway clean interdit.",
				this.cors.allowedOrigins());
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
