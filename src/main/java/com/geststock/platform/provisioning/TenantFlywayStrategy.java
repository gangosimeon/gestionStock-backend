package com.geststock.platform.provisioning;

import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.stereotype.Component;

import com.geststock.platform.company.LegacyCompanyRegistration;
import com.geststock.tenancy.DefaultTenant;
import com.geststock.tenancy.TenantDirectory;
import com.geststock.tenancy.TenantRef;

/**
 * Au démarrage, Flyway métier est joué pour <b>chaque</b> entreprise active (au lieu d'une base unique).
 * L'échec d'une entreprise est journalisé sans empêcher les autres de démarrer, sauf pour l'entreprise
 * par défaut (dev/test), dont le schéma doit être à jour pour la validation Hibernate.
 */
@Component
class TenantFlywayStrategy implements FlywayMigrationStrategy {

	private static final Logger log = LoggerFactory.getLogger(TenantFlywayStrategy.class);

	private final LegacyCompanyRegistration legacy;

	private final TenantDirectory directory;

	private final TenantMigrations migrations;

	private final DefaultTenant defaultTenant;

	TenantFlywayStrategy(LegacyCompanyRegistration legacy, TenantDirectory directory, TenantMigrations migrations,
			DefaultTenant defaultTenant) {
		this.legacy = legacy;
		this.directory = directory;
		this.migrations = migrations;
		this.defaultTenant = defaultTenant;
	}

	@Override
	public void migrate(Flyway flyway) {
		this.legacy.ensureRegistered();
		TenantRef defaultRef = this.defaultTenant.get().orElse(null);
		List<String> failed = new ArrayList<>();
		for (TenantRef tenant : this.directory.activeTenants()) {
			try {
				this.migrations.migrate(tenant, flyway.getConfiguration());
			}
			catch (RuntimeException ex) {
				if (tenant.equals(defaultRef)) {
					throw ex;
				}
				failed.add(tenant.code());
				log.error("Migration impossible pour l'entreprise « {} » : {}", tenant.code(), ex.getMessage());
			}
		}
		if (!failed.isEmpty()) {
			log.error("Entreprises non migrées (à traiter avant usage) : {}", failed);
		}
	}

}
