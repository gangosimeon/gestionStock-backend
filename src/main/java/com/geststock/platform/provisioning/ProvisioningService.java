package com.geststock.platform.provisioning;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.geststock.platform.CredentialCipher;
import com.geststock.platform.PlatformDb;
import com.geststock.platform.audit.PlatformAudit;
import com.geststock.platform.company.CompanyDirectory;
import com.geststock.tenancy.JdbcUrls;
import com.geststock.tenancy.TenancyProperties;
import com.geststock.tenancy.TenantContext;
import com.geststock.tenancy.TenantDataSourceRegistry;
import com.geststock.tenancy.TenantRef;
import com.geststock.user.UserAccountService;

/**
 * Provisionnement d'une entreprise, par étapes enregistrées et reprenables :
 * {@code DATABASE_RECORD} → {@code ROLE} → {@code DATABASE} → {@code MIGRATIONS} → {@code COMPANY_SETTINGS}
 * → {@code ADMIN_ACCOUNT} → {@code ACTIVATION}.
 *
 * <p>Chaque étape constate l'existant avant d'agir : une relance après échec reprend sans créer une
 * seconde base, un second rôle ni un second administrateur. Aucune transaction ne couvre l'ensemble
 * ({@code CREATE DATABASE} ne s'exécute pas dans une transaction) ; en cas d'échec, rien n'est supprimé
 * (pas de compensation destructive) : l'opération est relancée. Un seul traitement à la fois par job
 * (prise de job atomique en base).
 */
@Service
public class ProvisioningService implements ApplicationRunner, DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(ProvisioningService.class);

	static final String[] STEPS = { "DATABASE_RECORD", "ROLE", "DATABASE", "MIGRATIONS", "COMPANY_SETTINGS",
			"ADMIN_ACCOUNT", "ACTIVATION" };

	private static final String PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

	private final PlatformDb platform;

	private final ProvisionerAccess provisioner;

	private final TenantMigrations migrations;

	private final CredentialCipher cipher;

	private final CompanyDirectory directory;

	private final TenantDataSourceRegistry pools;

	private final UserAccountService accounts;

	private final NamedParameterJdbcTemplate tenantJdbc;

	private final ObjectProvider<Flyway> flyway;

	private final PlatformAudit audit;

	private final TenancyProperties tenancy;

	private final Clock clock;

	private final boolean async;

	private final ExecutorService executor = new ThreadPoolExecutor(1, 2, 60, TimeUnit.SECONDS,
			new LinkedBlockingQueue<>(50), runnable -> {
				Thread thread = new Thread(runnable, "provisioning");
				thread.setDaemon(true);
				return thread;
			});

	private final SecureRandom random = new SecureRandom();

	ProvisioningService(PlatformDb platform, ProvisionerAccess provisioner, TenantMigrations migrations,
			CredentialCipher cipher, CompanyDirectory directory, TenantDataSourceRegistry pools, UserAccountService accounts,
			NamedParameterJdbcTemplate tenantJdbc, ObjectProvider<Flyway> flyway, PlatformAudit audit,
			TenancyProperties tenancy, Clock clock, Environment environment) {
		this.platform = platform;
		this.provisioner = provisioner;
		this.migrations = migrations;
		this.cipher = cipher;
		this.directory = directory;
		this.pools = pools;
		this.accounts = accounts;
		this.tenantJdbc = tenantJdbc;
		this.flyway = flyway;
		this.audit = audit;
		this.tenancy = tenancy;
		this.clock = clock;
		this.async = environment.getProperty("geststock.platform.provisioning.async", Boolean.class, true);
	}

	/** Au démarrage : un job resté « RUNNING » (arrêt brutal) est marqué en échec, donc relançable. */
	@Override
	public void run(ApplicationArguments args) {
		int interrupted = this.platform.jdbc()
			.sql("update platform.provisioning_job set status = 'FAILED', last_error = :message, finished_at = now() where status = 'RUNNING'")
			.param("message", "Interrompu par un redémarrage du serveur : relancer le provisionnement.")
			.update();
		if (interrupted > 0) {
			this.platform.jdbc()
				.sql("update platform.company set status = 'FAILED', updated_at = now() where status = 'PROVISIONING'")
				.update();
			log.warn("{} provisionnement(s) interrompu(s) par le redémarrage, marqué(s) en échec.", interrupted);
		}
	}

	/** Lance (ou relance) le provisionnement d'une entreprise ; en arrière-plan sauf configuration contraire. */
	public void start(UUID companyId) {
		if (this.async) {
			this.executor.submit(() -> runSafely(companyId));
		}
		else {
			runSafely(companyId);
		}
	}

	private void runSafely(UUID companyId) {
		try {
			execute(companyId);
		}
		catch (RuntimeException ex) {
			log.error("Provisionnement de l'entreprise {} : erreur inattendue ({}).", companyId, ex.getClass().getSimpleName());
		}
	}

	/** Exécute les étapes restantes. Sans effet si le job n'est pas en attente ou en échec (déjà pris). */
	void execute(UUID companyId) {
		Integer attempt = this.platform.jdbc()
			.sql("""
					update platform.provisioning_job
					set status = 'RUNNING', attempts = attempts + 1, started_at = now(), finished_at = null, last_error = null
					where company_id = :company and status in ('PENDING', 'FAILED')
					returning attempts""")
			.param("company", companyId)
			.query(Integer.class)
			.optional()
			.orElse(null);
		if (attempt == null) {
			return;
		}
		setCompanyStatus(companyId, "PROVISIONING", null);
		Company company = loadCompany(companyId);
		String step = null;
		try {
			for (String current : STEPS) {
				step = current;
				setCurrentStep(companyId, step);
				String result = runStep(step, company);
				recordStep(companyId, step, result.startsWith("SKIPPED") ? "SKIPPED" : "DONE", result, attempt);
			}
			this.platform.jdbc()
				.sql("update platform.provisioning_job set status = 'SUCCEEDED', current_step = null, finished_at = now() where company_id = :company")
				.param("company", companyId)
				.update();
			this.audit.record("COMPANY_PROVISIONED", companyId, "Entreprise " + company.code() + " active", null, "system");
			log.info("Entreprise « {} » provisionnée.", company.code());
		}
		catch (RuntimeException ex) {
			String message = safeMessage(ex);
			recordStep(companyId, step, "FAILED", message, attempt);
			this.platform.jdbc()
				.sql("update platform.provisioning_job set status = 'FAILED', last_error = :message, finished_at = now() where company_id = :company")
				.param("message", message)
				.param("company", companyId)
				.update();
			setCompanyStatus(companyId, "FAILED", null);
			this.audit.record("COMPANY_PROVISIONING_FAILED", companyId, step + " : " + message, null, "system");
			log.error("Provisionnement de « {} » en échec à l'étape {} : {}", company.code(), step, message);
		}
	}

	private String runStep(String step, Company company) {
		return switch (step) {
			case "DATABASE_RECORD" -> ensureDatabaseRecord(company);
			case "ROLE" -> ensureRole(company);
			case "DATABASE" -> ensureDatabase(company);
			case "MIGRATIONS" -> migrate(company);
			case "COMPANY_SETTINGS" -> initCompanySettings(company);
			case "ADMIN_ACCOUNT" -> ensureAdmin(company);
			case "ACTIVATION" -> activate(company);
			default -> throw new IllegalStateException("Étape inconnue : " + step);
		};
	}

	// --- Étapes ---------------------------------------------------------------------------------------

	private String ensureDatabaseRecord(Company company) {
		boolean exists = this.platform.jdbc()
			.sql("select count(*) from platform.company_database where company_id = :id")
			.param("id", company.id())
			.query(Integer.class)
			.single() > 0;
		if (exists) {
			return "SKIPPED : base déjà enregistrée";
		}
		if (!this.cipher.isConfigured()) {
			throw new ProvisioningException("Clé GESTSTOCK_TENANT_CREDENTIALS_KEY absente : impossible de créer une base dédiée.");
		}
		String database = JdbcUrls.requireSafeIdentifier(this.tenancy.databasePrefix() + String.format("%04d", company.number()));
		String role = JdbcUrls.requireSafeIdentifier(database + "_app");
		this.platform.jdbc()
			.sql("""
					insert into platform.company_database (company_id, mode, database_name, runtime_role, runtime_password)
					values (:id, 'DEDICATED', :database, :role, :password)""")
			.param("id", company.id())
			.param("database", database)
			.param("role", role)
			.param("password", this.cipher.encrypt(generatePassword()))
			.update();
		return "Base " + database + " enregistrée (rôle " + role + ")";
	}

	private String ensureRole(Company company) {
		DatabaseRecord db = databaseRecord(company.id());
		String password = this.cipher.decrypt(db.encryptedPassword());
		boolean exists = this.provisionerQuery("select count(*) from pg_roles where rolname = ?", db.role()) > 0;
		// Mot de passe toujours réaligné sur celui stocké (reprise après un échec partiel).
		String verb = exists ? "ALTER ROLE " : "CREATE ROLE ";
		// Vérificateur SCRAM pré-calculé : le mot de passe en clair n'est jamais envoyé au serveur.
		executeDdl(verb + quote(db.role()) + " WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD '"
				+ ScramSha256.verifier(password) + "'");
		return exists ? "Rôle " + db.role() + " déjà présent (mot de passe réaligné)" : "Rôle " + db.role() + " créé";
	}

	private String ensureDatabase(Company company) {
		DatabaseRecord db = databaseRecord(company.id());
		boolean exists = this.provisionerQuery("select count(*) from pg_database where datname = ?", db.name()) > 0;
		if (!exists) {
			executeDdl("CREATE DATABASE " + quote(db.name()) + " ENCODING 'UTF8'");
		}
		// Seul le rôle de l'entreprise (et le provisionnement) peut se connecter à cette base.
		executeDdl("REVOKE CONNECT, TEMPORARY ON DATABASE " + quote(db.name()) + " FROM PUBLIC");
		executeDdl("GRANT CONNECT ON DATABASE " + quote(db.name()) + " TO " + quote(db.role()));
		return exists ? "SKIPPED : base " + db.name() + " déjà créée (droits de connexion vérifiés)" : "Base " + db.name() + " créée";
	}

	private String migrate(Company company) {
		var result = this.migrations.migrate(company.ref(), this.flyway.getObject().getConfiguration());
		return result.migrationsExecuted > 0 ? result.migrationsExecuted + " migration(s) appliquée(s)"
				: "SKIPPED : schéma déjà à jour";
	}

	/** Coordonnées de l'entreprise reportées sur les documents imprimés, uniquement si elles sont vides. */
	private String initCompanySettings(Company company) {
		int updated = TenantContext.callAs(company.ref(), () -> this.tenantJdbc.update("""
				update invoice_setting
				set company_name = :tradeName, legal_name = :legalName, address = :address, city = :city,
				    country = :country, phone = :phone, email = :email, website = :website, tax_id = :taxId,
				    currency_label = :currency
				where company_name = ''""", Map.ofEntries(Map.entry("tradeName", company.tradeName()),
				Map.entry("legalName", company.legalName()), Map.entry("address", blank(company.address())),
				Map.entry("city", blank(company.city())), Map.entry("country", blank(company.country())),
				Map.entry("phone", blank(company.phone())), Map.entry("email", blank(company.email())),
				Map.entry("website", blank(company.website())), Map.entry("taxId", blank(company.taxId())),
				Map.entry("currency", company.currency()))));
		return updated > 0 ? "Coordonnées reportées sur les documents" : "SKIPPED : coordonnées déjà renseignées";
	}

	private String ensureAdmin(Company company) {
		UUID id = TenantContext.callAs(company.ref(), () -> this.accounts.createCompanyAdministrator(company.adminUsername(),
				company.ownerFirstName(), company.ownerLastName(), company.ownerEmail(), company.ownerPhone()));
		return "Administrateur " + company.adminUsername() + " prêt (" + id + ") : lien d'activation à générer";
	}

	private String activate(Company company) {
		setCompanyStatus(company.id(), "ACTIVE", null);
		return "Entreprise active";
	}

	// --- Outils ---------------------------------------------------------------------------------------

	private int provisionerQuery(String sql, String parameter) {
		try (Connection connection = this.provisioner.server().getConnection();
				var statement = connection.prepareStatement(sql)) {
			statement.setString(1, parameter);
			try (var rs = statement.executeQuery()) {
				rs.next();
				return rs.getInt(1);
			}
		}
		catch (SQLException ex) {
			throw new ProvisioningException(sqlMessage(ex));
		}
	}

	/** DDL hors transaction, sur la base d'administration ; message d'erreur sans l'instruction (mot de passe). */
	private void executeDdl(String ddl) {
		try (Connection connection = this.provisioner.server().getConnection(); Statement statement = connection.createStatement()) {
			connection.setAutoCommit(true);
			statement.execute(ddl);
		}
		catch (SQLException ex) {
			throw new ProvisioningException(sqlMessage(ex));
		}
	}

	private String sqlMessage(SQLException ex) {
		if ("28P01".equals(ex.getSQLState())) {
			// Connexion refusée : le compte réellement utilisé aide à diagnostiquer (jamais le mot de passe).
			return "PostgreSQL (28P01) : authentification refusée pour " + this.provisioner.usernameHint()
					+ this.provisioner.passwordHint();
		}
		String message = ex.getMessage() != null ? ex.getMessage().lines().findFirst().orElse("") : "";
		if (message.toLowerCase().contains("password")) {
			message = "erreur SQL";
		}
		return "PostgreSQL (" + ex.getSQLState() + ") : " + message;
	}

	static String safeMessage(RuntimeException ex) {
		if (ex instanceof ProvisioningException) {
			return truncate(ex.getMessage());
		}
		String message = ex.getMessage() != null ? ex.getMessage().lines().findFirst().orElse("") : "";
		if (message.toLowerCase().contains("password")) {
			message = "";
		}
		return truncate(ex.getClass().getSimpleName() + (message.isBlank() ? "" : " : " + message));
	}

	private static String truncate(String message) {
		return message.length() > 480 ? message.substring(0, 480) : message;
	}

	private String generatePassword() {
		StringBuilder builder = new StringBuilder(32);
		for (int i = 0; i < 32; i++) {
			builder.append(PASSWORD_ALPHABET.charAt(this.random.nextInt(PASSWORD_ALPHABET.length())));
		}
		return builder.toString();
	}

	static String quote(String identifier) {
		return "\"" + JdbcUrls.requireSafeIdentifier(identifier) + "\"";
	}

	private static String blank(String value) {
		return value != null ? value : "";
	}

	private void setCompanyStatus(UUID companyId, String status, String reason) {
		this.platform.jdbc()
			.sql("update platform.company set status = :status, status_reason = :reason, updated_at = now() where id = :id")
			.param("status", status)
			.param("reason", reason)
			.param("id", companyId)
			.update();
		this.directory.invalidate(companyId);
		if (!"ACTIVE".equals(status)) {
			this.pools.close(companyId);
		}
	}

	private void setCurrentStep(UUID companyId, String step) {
		this.platform.jdbc()
			.sql("update platform.provisioning_job set current_step = :step where company_id = :company")
			.param("step", step)
			.param("company", companyId)
			.update();
	}

	private void recordStep(UUID companyId, String step, String status, String message, int attempt) {
		this.platform.jdbc()
			.sql("""
					insert into platform.provisioning_step (job_id, step, status, message, attempt, occurred_at)
					select id, :step, :status, :message, :attempt, :now from platform.provisioning_job where company_id = :company""")
			.param("step", step != null ? step : "INIT")
			.param("status", status)
			.param("message", truncate(message))
			.param("attempt", attempt)
			.param("now", Timestamp.from(Instant.now(this.clock)))
			.param("company", companyId)
			.update();
	}

	private Company loadCompany(UUID id) {
		return this.platform.jdbc()
			.sql("""
					select id, number, code, legal_name, trade_name, address, city, country, phone, email, website, tax_id,
					       currency, owner_first_name, owner_last_name, owner_email, owner_phone, admin_username
					from platform.company where id = :id""")
			.param("id", id)
			.query((rs, n) -> new Company(rs.getObject("id", UUID.class), rs.getInt("number"), rs.getString("code"),
					rs.getString("legal_name"), rs.getString("trade_name"), rs.getString("address"), rs.getString("city"),
					rs.getString("country"), rs.getString("phone"), rs.getString("email"), rs.getString("website"),
					rs.getString("tax_id"), rs.getString("currency"), rs.getString("owner_first_name"),
					rs.getString("owner_last_name"), rs.getString("owner_email"), rs.getString("owner_phone"),
					rs.getString("admin_username")))
			.single();
	}

	private DatabaseRecord databaseRecord(UUID companyId) {
		return this.platform.jdbc()
			.sql("select database_name, runtime_role, runtime_password from platform.company_database where company_id = :id and mode = 'DEDICATED'")
			.param("id", companyId)
			.query((rs, n) -> new DatabaseRecord(rs.getString("database_name"), rs.getString("runtime_role"),
					rs.getString("runtime_password")))
			.optional()
			.orElseThrow(() -> new ProvisioningException("Aucune base dédiée enregistrée pour cette entreprise."));
	}

	@Override
	public void destroy() {
		this.executor.shutdownNow();
	}

	private record Company(UUID id, int number, String code, String legalName, String tradeName, String address,
			String city, String country, String phone, String email, String website, String taxId, String currency,
			String ownerFirstName, String ownerLastName, String ownerEmail, String ownerPhone, String adminUsername) {

		TenantRef ref() {
			return new TenantRef(this.id, this.code);
		}

	}

	private record DatabaseRecord(String name, String role, String encryptedPassword) {

		@Override
		public String toString() {
			return "DatabaseRecord[name=" + this.name + ", role=" + this.role + ", password=***]";
		}

	}

	/** Échec d'une étape, message sûr (jamais de secret). */
	static class ProvisioningException extends RuntimeException {

		ProvisioningException(String message) {
			super(message);
		}

	}

}
