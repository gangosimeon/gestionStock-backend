package com.geststock.integration;

import java.util.UUID;

/**
 * Valeur de disponibilité d'un produit (schéma {@code Availability} du contrat), calculée <b>au moment de
 * servir la page</b> pour un événement mince {@code AVAILABILITY} (P3). Appelé dans la transaction de
 * lecture du flux, donc sur le même instantané que la page. Implémentation réelle : lot 1, étape 4c.
 */
@FunctionalInterface
public interface AvailabilityProvider {

	/** Disponibilité courante du produit ; jamais {@code null}. Sérialisée par le {@code JsonMapper}. */
	Object availability(UUID productId);

}
