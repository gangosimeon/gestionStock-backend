package com.geststock.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Plateforme multi-entreprises ({@code geststock.platform.*}). Aucun secret n'a de valeur par défaut.
 *
 * @param datasource      base plateforme ; URL vide (dev/test seulement) : schéma {@code platform} de la
 *                        base historique
 * @param provisioner     compte PostgreSQL de provisionnement (CREATEDB, CREATEROLE) ; vide (dev/test) :
 *                        identifiants de la base historique
 * @param credentialsKey  clé AES-256 en Base64 (32 octets) chiffrant les mots de passe des rôles
 *                        d'exécution ({@code GESTSTOCK_TENANT_CREDENTIALS_KEY})
 * @param legacyCompany   enregistrement de la base historique comme entreprise
 * @param bootstrapAdmin  premier Super Admin
 */
@ConfigurationProperties(prefix = "geststock.platform")
public record PlatformProperties(@DefaultValue Datasource datasource, @DefaultValue Datasource provisioner,
		String credentialsKey, @DefaultValue LegacyCompany legacyCompany, @DefaultValue BootstrapAdmin bootstrapAdmin) {

	/**
	 * @param url      URL JDBC
	 * @param username utilisateur
	 * @param password mot de passe
	 * @param poolSize connexions au plus
	 */
	public record Datasource(String url, String username, String password, @DefaultValue("5") int poolSize) {

		public boolean isConfigured() {
			return this.url != null && !this.url.isBlank();
		}

		@Override
		public String toString() {
			return "Datasource[url=" + this.url + ", username=" + this.username + ", password=***]";
		}

	}

	/**
	 * @param code code public donné à la base historique (vide : non enregistrée)
	 * @param name nom affiché
	 */
	public record LegacyCompany(String code, @DefaultValue("Entreprise principale") String name) {

		public boolean isConfigured() {
			return this.code != null && !this.code.isBlank();
		}

	}

	/**
	 * @param username identifiant ({@code GESTSTOCK_PLATFORM_ADMIN_USERNAME})
	 * @param password mot de passe initial ({@code GESTSTOCK_PLATFORM_ADMIN_PASSWORD}), à changer
	 * @param fullName nom affiché
	 */
	public record BootstrapAdmin(String username, String password, @DefaultValue("Super Admin") String fullName) {

		public boolean isConfigured() {
			return this.username != null && !this.username.isBlank() && this.password != null && !this.password.isBlank();
		}

		@Override
		public String toString() {
			return "BootstrapAdmin[username=" + this.username + ", password=***]";
		}

	}

}
