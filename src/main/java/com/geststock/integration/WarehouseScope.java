package com.geststock.integration;

import java.util.Set;
import java.util.UUID;

/**
 * Dépôts dont le stock compose la disponibilité servie ({@code Availability}) à une liaison, calculés <b>au moment
 * de servir la page</b> : dépôts de la liaison encore actifs (lot 2, étape G4a ; au lot 1 : tous les dépôts actifs).
 */
@FunctionalInterface
public interface WarehouseScope {

	/** Identifiants des dépôts du périmètre ; vide : aucune disponibilité. */
	Set<UUID> warehouseIds(LinkRef link);

}
