package com.geststock.integration;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Valeur de disponibilité des produits (schéma {@code Availability} du contrat), calculée <b>au moment de
 * servir la page</b> pour les événements minces {@code AVAILABILITY} (P3). Appelé <b>une fois par page</b>, pour
 * tous les produits concernés, dans la transaction de lecture du flux : même instantané que la page.
 */
@FunctionalInterface
public interface AvailabilityProvider {

	/**
	 * @param link       liaison qui lit : seuls ses dépôts encore actifs comptent ({@link WarehouseScope}, G4a)
	 * @param productIds produits des événements {@code AVAILABILITY} servis dans la page (non vide)
	 * @return une valeur par produit demandé, jamais {@code null} ; sérialisée par le {@code JsonMapper}
	 */
	Map<UUID, ?> availability(LinkRef link, Set<UUID> productIds);

}
