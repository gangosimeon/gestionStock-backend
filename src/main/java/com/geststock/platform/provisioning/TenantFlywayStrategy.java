package com.geststock.platform.provisioning;

import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.stereotype.Component;

import com.geststock.platform.company.LegacyCompanyRegistration;
import com.geststock.tenancy.DefaultTenant;
import com.geststock.tenancy.TenantDirectory;
import com.geststock.tenancy.TenantRef;
import com.geststock.tenancy.TenantSchemaRegistry;

/**
 * Au démarrage, Flyway métier est joué pour <b>chaque</b> entreprise active (au lieu d'une base unique).
 * L'échec d'une entreprise est journalisé sans empêcher les autres de démarrer, sauf pour l'entreprise
 * par défaut (dev/test), dont le schéma doit être à jour pour la validation Hibernate.
 *
 * <p>{@code geststock.tenancy.migrate-on-startup=false} (sans entreprise par défaut) les reporte juste
 * après le démarrage, en arrière-plan ({@link SchemaUpgradeService}) : démarrage rapide là où le délai
 * d'ouverture du port est court (Vercel).
 *
 * <p>Dans les deux cas, l'état de chaque base est tenu dans {@link TenantSchemaRegistry} : une base en
 * échec est refusée (503) au lieu de servir le code déployé sur un schéma en retard.
 */
@Component
class TenantFlywayStrategy implements FlywayMigrationStrategy {

	private static final Logger log = LoggerFactory.getLogger(TenantFlywayStrategy.class);

	private final LegacyCompanyRegistration legacy;

	private final TenantDirectory directory;

	private final TenantMigrations migrations;

	private final DefaultTenant defaultTenant;

	private final TenantSchemaRegistry registry;

	private final boolean migrateOnStartup;

	TenantFlywayStrategy(LegacyCompanyRegistration legacy, TenantDirectory directory, TenantMigrations migrations,
			DefaultTenant defaultTenant, TenantSchemaRegistry registry,
			@Value("${geststock.tenancy.migrate-on-startup:true}") boolean migrateOnStartup) {
		this.legacy = legacy;
		this.directory = directory;
		this.migrations = migrations;
		this.defaultTenant = defaultTenant;
		this.registry = registry;
		this.migrateOnStartup = migrateOnStartup;
	}

	@Override
	public void migrate(Flyway flyway) {
		this.legacy.ensureRegistered();
		TenantRef defaultRef = this.defaultTenant.get().orElse(null);
		if (!this.migrateOnStartup && defaultRef == null) {
			// Démarrage rapide : mise à jour en arrière-plan dès que le serveur est prêt (SchemaUpgradeService).
			log.info("Migrations des entreprises reportées après le démarrage (geststock.tenancy.migrate-on-startup=false).");
			return;
		}
		List<String> failed = new ArrayList<>();
		for (TenantRef tenant : this.directory.activeTenants()) {
			try {
				int applied = this.migrations.migrate(tenant, flyway.getConfiguration()).migrationsExecuted;
				this.registry.upToDate(tenant, applied, applied);
			}
			catch (RuntimeException ex) {
				if (tenant.equals(defaultRef)) {
					throw ex;
				}
				failed.add(tenant.code());
				this.registry.failed(tenant, 0, ProvisioningService.safeMessage(ex));
				log.error("Migration impossible pour l'entreprise « {} » : {}", tenant.code(), ex.getMessage());
			}
		}
		if (!failed.isEmpty()) {
			log.error("Entreprises non migrées (à traiter avant usage) : {}", failed);
		}
	}

}
