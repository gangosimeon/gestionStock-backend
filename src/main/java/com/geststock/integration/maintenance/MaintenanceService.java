package com.geststock.integration.maintenance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;

import com.geststock.integration.IntegrationPurgeProperties;

/**
 * Passage de maintenance de l'intégration (lot 3, G20 ; plan § 33.3) : bail pris (sinon
 * {@link MaintenanceInProgressException}), travail, bail libéré même en cas d'erreur, rapport agrégé.
 *
 * <p>G20a : aucun travail encore ; l'ordre des entreprises ({@link MaintenanceCompanies}), la purge, les mesures et le
 * nettoyage viennent avec G20b à G20d, sous ce bail.
 */
@Service
class MaintenanceService {

	private static final Logger log = LoggerFactory.getLogger(MaintenanceService.class);

	private final MaintenanceLease lease;

	private final MaintenanceAuthFailures failures;

	private final IntegrationMaintenanceProperties properties;

	private final IntegrationPurgeProperties purge;

	MaintenanceService(MaintenanceLease lease, MaintenanceAuthFailures failures, IntegrationMaintenanceProperties properties,
			IntegrationPurgeProperties purge) {
		this.lease = lease;
		this.failures = failures;
		this.properties = properties;
		this.purge = purge;
	}

	MaintenanceReport run() {
		long started = System.nanoTime();
		MaintenanceLease.Lease held = this.lease.acquire(this.properties.leaseTtl())
			.orElseThrow(MaintenanceInProgressException::new);
		try {
			// G20b et suivants : travail par entreprise, bail vérifié avant chaque entreprise et chaque lot.
		}
		finally {
			if (!this.lease.release(held)) {
				log.warn("Maintenance : bail du passage {} déjà échu ou repris à la libération.", held.generation());
			}
		}
		long durationMs = (System.nanoTime() - started) / 1_000_000;
		log.info("Maintenance : passage {} terminé en {} ms.", held.generation(), durationMs);
		return new MaintenanceReport("COMPLETED", this.purge.mode().name(), held.generation(), durationMs,
				new MaintenanceReport.AuthFailures(this.failures.drain(), "INSTANCE"));
	}

	/** Bail tenu par un autre passage : {@code 409 MAINTENANCE_IN_PROGRESS}, sans travail. */
	static final class MaintenanceInProgressException extends RuntimeException {

		MaintenanceInProgressException() {
			super("Passage de maintenance déjà en cours", null, false, false);
		}

	}

}
