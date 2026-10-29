package com.geststock.integration;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

/** Aucune réservation jusqu'au lot 5 (réservation des commandes en ligne). */
@Component
class NoReservedQuantities implements ReservedQuantities {

	@Override
	public Map<UUID, Map<UUID, Integer>> reserved(Set<UUID> productIds, Set<UUID> warehouseIds) {
		return Map.of();
	}

}
