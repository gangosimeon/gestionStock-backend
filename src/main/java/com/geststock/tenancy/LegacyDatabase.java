package com.geststock.tenancy;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.stereotype.Component;

/**
 * Base historique ({@code spring.datasource.*}, ou conteneur de test) : base de l'entreprise n°1 et
 * valeur de repli des connexions plateforme et provisionnement en dev/test.
 */
@Component
public class LegacyDatabase {

	private final String url;

	private final String username;

	private final String password;

	LegacyDatabase(ObjectProvider<JdbcConnectionDetails> connectionDetails, DataSourceProperties properties) {
		JdbcConnectionDetails details = connectionDetails.getIfAvailable();
		if (details != null) {
			this.url = details.getJdbcUrl();
			this.username = details.getUsername();
			this.password = details.getPassword();
		}
		else {
			this.url = properties.determineUrl();
			this.username = properties.determineUsername();
			this.password = properties.determinePassword();
		}
	}

	public String url() {
		return this.url;
	}

	public TenantConnection connection() {
		return new TenantConnection(this.url, this.username, this.password, true);
	}

	/** Nom de la base dans l'URL (affichage et contrôle d'unicité). */
	public String databaseName() {
		return JdbcUrls.databaseName(this.url);
	}

	@Override
	public String toString() {
		return "LegacyDatabase[url=" + this.url + ", username=" + this.username + ", password=***]";
	}

}
