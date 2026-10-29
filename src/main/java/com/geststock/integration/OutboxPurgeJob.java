package com.geststock.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.geststock.tenancy.TenantContext;
import com.geststock.tenancy.TenantDirectory;
import com.geststock.tenancy.TenantRef;

/**
 * Passage planifié de la purge ({@link OutboxPurge}) sur chaque entreprise active : n'existe que si
 * {@code geststock.integration.purge.enabled=true} (désactivée par défaut). Une entreprise en erreur est
 * journalisée et n'empêche pas les suivantes.
 */
@Component
@ConditionalOnProperty(prefix = "geststock.integration.purge", name = "enabled", havingValue = "true")
class OutboxPurgeJob {

	private static final Logger log = LoggerFactory.getLogger(OutboxPurgeJob.class);

	private final TenantDirectory directory;

	private final OutboxPurge purge;

	OutboxPurgeJob(TenantDirectory directory, OutboxPurge purge) {
		this.directory = directory;
		this.purge = purge;
	}

	@Scheduled(fixedDelayString = "${geststock.integration.purge.interval:1h}",
			initialDelayString = "${geststock.integration.purge.interval:1h}")
	void run() {
		for (TenantRef tenant : this.directory.activeTenants()) {
			try {
				TenantContext.runAs(tenant, this.purge::run);
			}
			catch (RuntimeException ex) {
				log.warn("Purge du flux d'intégration impossible pour l'entreprise {} : {}", tenant.companyId(),
						ex.getClass().getSimpleName());
			}
		}
	}

}
