package com.geststock.integration;

import java.util.Set;
import java.util.UUID;

/**
 * Dépôts dont le stock compose la disponibilité servie ({@code Availability}), calculés <b>au moment de servir
 * la page</b>. Lot 1 : tous les dépôts actifs de l'entreprise ; le périmètre propre à chaque liaison viendra aux
 * lots 2 et 3.
 */
@FunctionalInterface
public interface WarehouseScope {

	/** Identifiants des dépôts du périmètre ; vide : aucune disponibilité. */
	Set<UUID> warehouseIds();

}
