package com.geststock.integration;

import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * Fournisseur provisoire : aucun événement {@code AVAILABILITY} n'est encore publié. Échoue explicitement
 * plutôt que de servir une valeur inventée. Remplacé par l'implémentation réelle à l'étape 4c.
 */
@Component
class PendingAvailabilityProvider implements AvailabilityProvider {

	@Override
	public Object availability(UUID productId) {
		throw new IllegalStateException("Calcul de disponibilité non disponible avant l'étape 4c du lot 1");
	}

}
