package com.geststock.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Paramètres de sécurité ({@code geststock.security.*}). Aucun secret n'a de valeur par
 * défaut : voir {@code application.yml} et le README pour les variables d'environnement.
 */
@ConfigurationProperties(prefix = "geststock.security")
public record GestStockSecurityProperties(@DefaultValue Jwt jwt, @DefaultValue Login login, @DefaultValue BootstrapAdmin bootstrapAdmin) {

	/**
	 * @param secret               clé HMAC-SHA256, au moins 32 octets ({@code GESTSTOCK_JWT_SECRET})
	 * @param issuer               émetteur inscrit et vérifié dans chaque jeton
	 * @param accessTokenTtl       durée de vie d'un jeton d'accès
	 * @param refreshTokenTtl      durée de vie d'un refresh token
	 * @param allowEphemeralSecret {@code true} (dev/test seulement) : sans secret, une clé
	 *                             aléatoire est générée au démarrage (sessions perdues au redémarrage)
	 */
	public record Jwt(String secret, @DefaultValue("geststock") String issuer,
			@DefaultValue("15m") Duration accessTokenTtl, @DefaultValue("7d") Duration refreshTokenTtl,
			@DefaultValue("false") boolean allowEphemeralSecret) {
	}

	/**
	 * @param maxFailedAttempts échecs consécutifs avant verrouillage
	 * @param lockDuration      durée du verrouillage
	 */
	public record Login(@DefaultValue("5") int maxFailedAttempts, @DefaultValue("15m") Duration lockDuration) {
	}

	/**
	 * Administrateur créé au démarrage s'il n'existe aucun administrateur actif.
	 *
	 * @param username identifiant ({@code GESTSTOCK_ADMIN_USERNAME})
	 * @param password mot de passe temporaire ({@code GESTSTOCK_ADMIN_PASSWORD}), à changer
	 * @param lastName nom affiché
	 */
	public record BootstrapAdmin(String username, String password, @DefaultValue("Administrateur") String lastName) {

		boolean isConfigured() {
			return this.username != null && !this.username.isBlank() && this.password != null && !this.password.isBlank();
		}

		@Override
		public String toString() {
			return "BootstrapAdmin[username=" + this.username + ", password=***]";
		}

	}

}
