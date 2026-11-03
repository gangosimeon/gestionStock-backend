package com.geststock.integration.maintenance;

import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

/**
 * Échecs d'authentification de la route de maintenance (G20-D14), comptés <b>en mémoire de l'instance</b> : aucune
 * écriture en base par échec (une rafale d'échecs ne prend aucune connexion du pool plateforme, et le vrai cron passe).
 * Observation seulement, pas une limite de sécurité. Journal WARN aux paliers 1, 10, 100, 1 000… : volume borné.
 */
@Component
public class MaintenanceAuthFailures {

	private static final Logger log = LoggerFactory.getLogger(MaintenanceAuthFailures.class);

	private final AtomicLong total = new AtomicLong();

	private final AtomicLong sinceReport = new AtomicLong();

	/** Un échec de plus ; jamais la valeur reçue. */
	public void record() {
		this.sinceReport.incrementAndGet();
		long count = this.total.incrementAndGet();
		if (isPowerOfTen(count)) {
			log.warn("Maintenance : {} échec(s) d'authentification sur cette instance depuis son démarrage.", count);
		}
	}

	/** Échecs depuis le démarrage de l'instance. */
	public long total() {
		return this.total.get();
	}

	/** Échecs depuis le rapport précédent, remis à zéro (champ {@code authFailures} du rapport). */
	long drain() {
		return this.sinceReport.getAndSet(0);
	}

	private static boolean isPowerOfTen(long value) {
		long power = 1;
		while (power < value && power <= Long.MAX_VALUE / 10) {
			power *= 10;
		}
		return power == value;
	}

}
