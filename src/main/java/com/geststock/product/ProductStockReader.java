package com.geststock.product;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Lecture des quantités en stock, fournie par le module {@code stock} (seule source de
 * vérité du stock). Le module produit ne fait que l'afficher : il n'écrit jamais de stock.
 *
 * <p>Inversion de dépendance : {@code stock} dépend de {@code product}, pas l'inverse. Tant
 * qu'aucune implémentation n'est présente, les quantités valent 0.
 */
public interface ProductStockReader {

	/**
	 * Quantités disponibles par produit.
	 *
	 * @param productIds  produits demandés
	 * @param warehouseId dépôt courant ({@code X-Warehouse-Id}) ; {@code null} = dépôt par défaut
	 * @return quantité par produit (absent = 0)
	 */
	Map<UUID, Integer> quantities(Collection<UUID> productIds, UUID warehouseId);

}
