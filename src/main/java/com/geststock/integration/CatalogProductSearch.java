package com.geststock.integration;

import java.util.List;

/**
 * Recherche de produits actifs de l'entreprise courante pour le rapprochement assisté (lot 3, étape G23 ;
 * contrat {@code GET /catalog/products/search}, portée {@code catalog:match}).
 *
 * <p>Chaque objet de la liste est sérialisable par Jackson au format du schéma {@code Product} du contrat,
 * produit par le <b>même code</b> que les événements {@code PRODUCT} et la section {@code products} de
 * l'instantané : une seule définition du format (G23-D1).
 *
 * <p>Fourni par le module des produits. Appelé dans la transaction de la requête ; aucune pagination par
 * curseur : la réponse se limite à {@code limit} résultats.
 */
public interface CatalogProductSearch {

	/**
	 * Produits actifs de l'entreprise courante dont le sku ou le nom contient {@code q} (insensible à la
	 * casse ; {@code %}, {@code _} et {@code \} du motif déjà échappés par l'appelant).
	 *
	 * @param pattern motif ILIKE déjà préparé (entouré de {@code %}, ESCAPE {@code \})
	 * @param limit   nombre maximal de résultats (1 à 50)
	 * @return au plus {@code limit} objets sérialisables, triés par nom puis par identifiant
	 */
	List<Object> search(String pattern, int limit);

}
