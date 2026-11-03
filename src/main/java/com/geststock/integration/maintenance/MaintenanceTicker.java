package com.geststock.integration.maintenance;

import org.springframework.stereotype.Component;

/**
 * Horloge monotone du budget d'un passage de maintenance (G20-D3) : {@link System#nanoTime()}, jamais l'heure murale.
 * Remplaçable dans les tests, sans toucher à l'horloge de l'application ({@code Clock}) qui date les jetons.
 */
@Component
class MaintenanceTicker {

	long nanoTime() {
		return System.nanoTime();
	}

}
