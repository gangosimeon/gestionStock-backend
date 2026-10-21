package com.geststock.platform.provisioning;

import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

import com.geststock.platform.PlatformDb;
import com.geststock.tenancy.JdbcUrls;
import com.geststock.tenancy.LegacyDatabase;
import com.geststock.tenancy.TenantRef;

/**
 * Migrations métier ({@code db/migration}) d'une base d'entreprise, avec la configuration Flyway de
 * l'application. Les bases dédiées sont migrées par le compte de provisionnement (propriétaire des
 * objets) ; le rôle d'exécution reçoit ensuite ses droits DML ({@link #grantRuntimePrivileges}).
 */
@Component
public class TenantMigrations {

	private static final Logger log = LoggerFactory.getLogger(TenantMigrations.class);

	private final PlatformDb platform;

	private final ProvisionerAccess provisioner;

	private final LegacyDatabase legacy;

	TenantMigrations(PlatformDb platform, ProvisionerAccess provisioner, LegacyDatabase legacy) {
		this.platform = platform;
		this.provisioner = provisioner;
		this.legacy = legacy;
	}

	public MigrateResult migrate(TenantRef tenant, Configuration base) {
		DatabaseInfo info = info(tenant.companyId());
		DataSource owner = info.legacy() ? legacyOwner() : this.provisioner.database(info.databaseName());
		var configuration = Flyway.configure().configuration(base).dataSource(owner);
		if (!info.legacy()) {
			// Jamais de données de démonstration (comptes, mots de passe connus) dans une base d'entreprise.
			configuration.locations(java.util.Arrays.stream(base.getLocations())
				.filter(location -> !location.getDescriptor().contains("db/seed"))
				.toArray(org.flywaydb.core.api.Location[]::new));
		}
		MigrateResult result = configuration.load().migrate();
		if (!info.legacy()) {
			grantRuntimePrivileges(info.databaseName(), info.runtimeRole());
		}
		if (result.migrationsExecuted > 0) {
			log.info("Entreprise « {} » : {} migration(s) appliquée(s).", tenant.code(), result.migrationsExecuted);
		}
		return result;
	}

	/**
	 * Droits du rôle d'exécution : DML sur les tables, séquences, fonctions ; aucun droit sur l'historique
	 * Flyway ; droits par défaut pour les objets des migrations futures. Idempotent.
	 */
	public void grantRuntimePrivileges(String databaseName, String runtimeRole) {
		String role = quote(JdbcUrls.requireSafeIdentifier(runtimeRole));
		JdbcTemplate jdbc = new JdbcTemplate(this.provisioner.database(databaseName));
		jdbc.execute("GRANT USAGE ON SCHEMA public TO " + role);
		jdbc.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " + role);
		jdbc.execute("GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO " + role);
		jdbc.execute("GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO " + role);
		jdbc.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO " + role);
		jdbc.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO " + role);
		jdbc.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
		jdbc.execute("REVOKE INSERT, UPDATE, DELETE ON public.flyway_schema_history FROM " + role);
	}

	private DataSource legacyOwner() {
		var c = this.legacy.connection();
		return new DriverManagerDataSource(c.jdbcUrl(), c.username(), c.password());
	}

	private DatabaseInfo info(UUID companyId) {
		return this.platform.jdbc()
			.sql("select mode, database_name, runtime_role from platform.company_database where company_id = :id")
			.param("id", companyId)
			.query((rs, n) -> new DatabaseInfo("LEGACY".equals(rs.getString("mode")), rs.getString("database_name"),
					rs.getString("runtime_role")))
			.single();
	}

	static String quote(String identifier) {
		return "\"" + identifier + "\"";
	}

	private record DatabaseInfo(boolean legacy, String databaseName, String runtimeRole) {
	}

}
