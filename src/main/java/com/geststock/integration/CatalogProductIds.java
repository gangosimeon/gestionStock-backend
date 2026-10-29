package com.geststock.integration;

import java.util.List;
import java.util.UUID;

/**
 * Identifiants des produits, archivés compris, par identifiant croissant : clé des sections {@code productImages}
 * et {@code availability} de l'instantané (une entrée par produit). Fourni par le module des produits.
 */
@FunctionalInterface
public interface CatalogProductIds {

	/** Au plus {@code limit} identifiants supérieurs à {@code afterId} (l'UUID nul au début). */
	List<UUID> idsAfter(UUID afterId, int limit);

}
