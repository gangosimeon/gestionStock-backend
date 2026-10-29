package com.geststock.integration;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Quantités réservées (commandes en ligne non converties), soustraites du stock pour calculer la
 * disponibilité. Aucune réservation avant le lot 5 ({@link NoReservedQuantities}).
 */
@FunctionalInterface
public interface ReservedQuantities {

	/**
	 * @return quantité réservée par produit puis par dépôt ; un couple absent vaut 0
	 */
	Map<UUID, Map<UUID, Integer>> reserved(Set<UUID> productIds, Set<UUID> warehouseIds);

}
