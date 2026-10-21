package com.geststock.tenancy;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Routage des bases d'entreprise ({@code geststock.tenancy.*}).
 *
 * @param defaultCompany code de l'entreprise visée sans contexte (outils et tests) ; <b>dev/test
 *                       uniquement</b>, refusé en production par {@code ProductionSafetyCheck}
 * @param databasePrefix préfixe des bases dédiées ({@code geststock_company_} → {@code geststock_company_0002})
 * @param pool           pools de connexions par entreprise
 */
@ConfigurationProperties(prefix = "geststock.tenancy")
public record TenancyProperties(String defaultCompany, @DefaultValue("geststock_company_") String databasePrefix,
		@DefaultValue Pool pool) {

	public TenancyProperties {
		if (!databasePrefix.matches("[a-z][a-z0-9_]{0,40}")) {
			throw new IllegalArgumentException("geststock.tenancy.database-prefix invalide : " + databasePrefix);
		}
	}

	public boolean hasDefaultCompany() {
		return this.defaultCompany != null && !this.defaultCompany.isBlank();
	}

	/**
	 * @param maxSize           connexions au plus par entreprise
	 * @param legacyMaxSize     connexions au plus pour la base historique
	 * @param maxPools          pools ouverts au plus (au-delà : éviction des pools inactifs, sinon refus)
	 * @param idleEviction      pool fermé après cette durée sans utilisation (et sans connexion active)
	 * @param connectionTimeout attente maximale d'une connexion libre
	 */
	public record Pool(@DefaultValue("5") int maxSize, @DefaultValue("10") int legacyMaxSize,
			@DefaultValue("50") int maxPools, @DefaultValue("15m") Duration idleEviction,
			@DefaultValue("10s") Duration connectionTimeout) {
	}

}
