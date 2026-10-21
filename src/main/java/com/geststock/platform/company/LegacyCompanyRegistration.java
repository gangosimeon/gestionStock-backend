package com.geststock.platform.company;

import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

import com.geststock.platform.PlatformDb;
import com.geststock.platform.PlatformProperties;
import com.geststock.tenancy.LegacyDatabase;

/**
 * Base historique enregistrée comme entreprise (mode {@code LEGACY}), sans copie ni migration
 * destructive : elle garde ses identifiants applicatifs ({@code GESTSTOCK_DB_*}) et ses données.
 * Idempotent : rien n'est fait si le code est déjà enregistré.
 */
@Component
public class LegacyCompanyRegistration {

	private static final Logger log = LoggerFactory.getLogger(LegacyCompanyRegistration.class);

	private final PlatformDb db;

	private final PlatformProperties.LegacyCompany settings;

	private final LegacyDatabase legacy;

	LegacyCompanyRegistration(PlatformDb db, PlatformProperties properties, LegacyDatabase legacy) {
		this.db = db;
		this.settings = properties.legacyCompany();
		this.legacy = legacy;
	}

	public void ensureRegistered() {
		if (!this.settings.isConfigured()) {
			return;
		}
		String code = this.settings.code().trim().toLowerCase(Locale.ROOT);
		this.db.inTransaction(() -> {
			boolean exists = this.db.jdbc()
				.sql("select count(*) from platform.company where code = :code")
				.param("code", code)
				.query(Integer.class)
				.single() > 0;
			if (exists) {
				return;
			}
			String databaseName = this.legacy.databaseName();
			boolean databaseTaken = this.db.jdbc()
				.sql("select count(*) from platform.company_database where database_name = :name")
				.param("name", databaseName)
				.query(Integer.class)
				.single() > 0;
			if (databaseTaken) {
				throw new IllegalStateException("La base historique « " + databaseName
						+ " » est déjà rattachée à une autre entreprise : vérifier geststock.platform.legacy-company.code.");
			}
			UUID id = UUID.randomUUID();
			this.db.jdbc()
				.sql("""
						insert into platform.company (id, code, legal_name, trade_name, owner_last_name, admin_username, status)
						values (:id, :code, :name, :name, :name, 'admin', 'ACTIVE')""")
				.param("id", id)
				.param("code", code)
				.param("name", this.settings.name())
				.update();
			this.db.jdbc()
				.sql("insert into platform.company_database (company_id, mode, database_name) values (:id, 'LEGACY', :name)")
				.param("id", id)
				.param("name", databaseName)
				.update();
			this.db.jdbc()
				.sql("insert into platform.company_branding (company_id) values (:id)")
				.param("id", id)
				.update();
			this.db.jdbc()
				.sql("insert into platform.platform_audit_log (action, company_id, details) values ('COMPANY_LEGACY_REGISTERED', :id, :details)")
				.param("id", id)
				.param("details", "Base historique " + databaseName + " rattachée au code " + code)
				.update();
			log.info("Base historique « {} » enregistrée comme entreprise « {} ».", databaseName, code);
		});
	}

}
