package com.geststock.platform.provisioning;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

import com.geststock.platform.PlatformProperties;
import com.geststock.platform.company.CompanyDirectory;
import com.geststock.tenancy.JdbcUrls;
import com.geststock.tenancy.LegacyDatabase;

/**
 * Compte PostgreSQL de provisionnement ({@code CREATEDB}, {@code CREATEROLE}) : création des bases et
 * rôles, migrations des bases dédiées. Jamais utilisé pour les requêtes métier. Connexions non poolées
 * (opérations rares) ; aucun identifiant n'est journalisé.
 */
@Component
public class ProvisionerAccess {

	private final PlatformProperties.Datasource settings;

	private final LegacyDatabase legacy;

	private final CompanyDirectory directory;

	ProvisionerAccess(PlatformProperties properties, LegacyDatabase legacy, CompanyDirectory directory) {
		this.settings = properties.provisioner();
		this.legacy = legacy;
		this.directory = directory;
	}

	/** Connexion à la base d'administration du serveur (celle de l'URL de provisionnement). */
	public DataSource server() {
		return dataSource(this.directory.serverUrl());
	}

	/** Connexion propriétaire à une base d'entreprise dédiée. */
	public DataSource database(String databaseName) {
		return dataSource(JdbcUrls.withDatabase(this.directory.serverUrl(), databaseName));
	}

	/** Rôle de provisionnement (propriétaire des objets des bases dédiées). */
	public String username() {
		return this.settings.isConfigured() ? this.settings.username() : this.legacy.connection().username();
	}

	/**
	 * Compte utilisé, affiché seulement s'il a la forme d'un nom de rôle PostgreSQL : une valeur
	 * inattendue (souvent un mot de passe collé dans la mauvaise variable) n'est jamais recopiée.
	 */
	String usernameHint() {
		String username = username();
		if (username == null || username.isBlank()) {
			return "un utilisateur vide";
		}
		if (ROLE_NAME.matcher(username).matches()) {
			return "« " + username + " »";
		}
		return "un utilisateur inattendu (" + username.length()
				+ " caractères, ressemble à un mot de passe : vérifier la variable USERNAME)";
	}

	private static final java.util.regex.Pattern ROLE_NAME = java.util.regex.Pattern.compile("[a-z_][a-z0-9_$]{0,62}");

	/**
	 * Indice de configuration après un refus d'authentification, sans rien révéler du mot de passe :
	 * source des identifiants, mot de passe absent ou entouré d'espaces (copier-coller).
	 */
	String passwordHint() {
		String source = this.settings.isConfigured() ? " (GESTSTOCK_PROVISIONER_DB_*)" : " (identifiants de la base historique)";
		String password = password();
		if (password == null || password.isEmpty()) {
			return source + " ; mot de passe vide";
		}
		if (!password.equals(password.strip())) {
			return source + " ; le mot de passe commence ou finit par un espace";
		}
		return source;
	}

	/** Retire d'un message les identifiants de connexion (mot de passe, et nom d'utilisateur s'il en a l'air). */
	String redact(String message) {
		if (message == null) {
			return "";
		}
		java.util.List<String> secrets = new java.util.ArrayList<>();
		String password = password();
		if (password != null && !password.isBlank()) {
			secrets.add(password);
		}
		String username = username();
		if (username != null && !username.isBlank() && !ROLE_NAME.matcher(username).matches()) {
			secrets.add(username);
		}
		// Le plus long d'abord : un secret court contenu dans un plus long ne le laisse pas à moitié visible.
		secrets.sort(java.util.Comparator.comparingInt(String::length).reversed());
		String result = message;
		for (String secret : secrets) {
			result = result.replace(secret, "***");
		}
		return result;
	}

	private String password() {
		return this.settings.isConfigured() ? this.settings.password() : this.legacy.connection().password();
	}

	private DataSource dataSource(String url) {
		return new DriverManagerDataSource(url, username(), password());
	}

}
