package com.geststock.platform.provisioning;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.geststock.platform.audit.PlatformAudit;
import com.geststock.tenancy.DefaultTenant;
import com.geststock.tenancy.TenantDirectory;
import com.geststock.tenancy.TenantRef;
import com.geststock.tenancy.TenantSchemaRegistry;

/**
 * Mise à jour du schéma de toutes les bases d'entreprise actives (migrations {@code db/migration}).
 *
 * <p>Quand les migrations ne sont pas jouées au démarrage ({@code geststock.tenancy.migrate-on-startup=false},
 * hébergement à délai de démarrage court comme Vercel), elles sont lancées <b>en arrière-plan juste
 * après le démarrage</b> : le serveur répond tout de suite, et seule une entreprise dont la base reçoit
 * des migrations est refusée (503) le temps de sa mise à jour ({@link TenantSchemaRegistry}). Une base
 * en échec reste refusée jusqu'à une nouvelle tentative réussie, relancée par le Super Admin.
 *
 * <p>Une seule exécution à la fois par instance ; Flyway verrouille en plus chaque base (plusieurs
 * instances possibles). Les bases sont traitées l'une après l'autre.
 */
@Service
public class SchemaUpgradeService implements DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(SchemaUpgradeService.class);

	/** État de la dernière exécution, pour l'écran Super Admin. */
	public record Status(boolean running, boolean automatic, Instant startedAt, Instant finishedAt,
			List<TenantSchemaRegistry.Entry> companies) {
	}

	private final TenantDirectory directory;

	private final TenantMigrations migrations;

	private final TenantSchemaRegistry registry;

	private final ObjectProvider<Flyway> flyway;

	private final PlatformAudit audit;

	private final Clock clock;

	/** Mise à jour automatique en arrière-plan après le démarrage (migrations non jouées au démarrage). */
	private final boolean automatic;

	private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "schema-upgrade");
		thread.setDaemon(true);
		return thread;
	});

	private final AtomicBoolean running = new AtomicBoolean();

	private volatile Instant startedAt;

	private volatile Instant finishedAt;

	SchemaUpgradeService(TenantDirectory directory, TenantMigrations migrations, TenantSchemaRegistry registry,
			ObjectProvider<Flyway> flyway, PlatformAudit audit, Clock clock, DefaultTenant defaultTenant,
			@Value("${geststock.tenancy.migrate-on-startup:true}") boolean migrateOnStartup) {
		this.directory = directory;
		this.migrations = migrations;
		this.registry = registry;
		this.flyway = flyway;
		this.audit = audit;
		this.clock = clock;
		this.automatic = !migrateOnStartup && defaultTenant.get().isEmpty();
	}

	@EventListener(ApplicationReadyEvent.class)
	void afterStartup() {
		if (this.automatic) {
			log.info("Mise à jour des bases des entreprises lancée en arrière-plan.");
			start();
		}
	}

	/**
	 * Lance la mise à jour de toutes les bases actives en arrière-plan.
	 *
	 * @return {@code false} si une mise à jour est déjà en cours (rien n'est relancé)
	 */
	public boolean start() {
		if (!this.running.compareAndSet(false, true)) {
			return false;
		}
		this.startedAt = Instant.now(this.clock);
		this.finishedAt = null;
		this.executor.submit(this::upgradeAll);
		return true;
	}

	/** Relance demandée par le Super Admin connecté : tracée dans le journal de la plateforme. */
	public boolean startByAdmin() {
		boolean started = start();
		if (started) {
			this.audit.record("SCHEMA_UPGRADE_STARTED", null, "Mise à jour des bases des entreprises relancée");
		}
		return started;
	}

	public Status status() {
		return new Status(this.running.get(), this.automatic, this.startedAt, this.finishedAt, this.registry.entries());
	}

	/** Toutes les bases actives, l'une après l'autre ; l'échec d'une base n'arrête pas les suivantes. */
	void upgradeAll() {
		try {
			Configuration base = this.flyway.getObject().getConfiguration();
			for (TenantRef tenant : this.directory.activeTenants()) {
				upgrade(tenant, base);
			}
		}
		catch (RuntimeException ex) {
			log.error("Mise à jour des bases interrompue : {}", ProvisioningService.safeMessage(ex));
		}
		finally {
			this.finishedAt = Instant.now(this.clock);
			this.running.set(false);
		}
	}

	/** Une base : vérification, puis migrations éventuelles ; l'entreprise est refusée pendant leur application. */
	void upgrade(TenantRef tenant, Configuration base) {
		int pending = 0;
		try {
			pending = this.migrations.pendingCount(tenant, base);
			if (pending > 0) {
				this.registry.upgrading(tenant, pending);
			}
			int applied = this.migrations.migrate(tenant, base).migrationsExecuted;
			this.registry.upToDate(tenant, pending, applied);
		}
		catch (RuntimeException ex) {
			String message = ProvisioningService.safeMessage(ex);
			this.registry.failed(tenant, pending, message);
			log.error("Mise à jour impossible pour l'entreprise « {} » : {}", tenant.code(), message);
		}
	}

	@Override
	public void destroy() {
		this.executor.shutdownNow();
	}

}
