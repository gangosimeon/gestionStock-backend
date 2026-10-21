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

	private DataSource dataSource(String url) {
		String username = username();
		String password = this.settings.isConfigured() ? this.settings.password() : this.legacy.connection().password();
		return new DriverManagerDataSource(url, username, password);
	}

}
