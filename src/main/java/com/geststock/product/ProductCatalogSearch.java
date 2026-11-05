package com.geststock.product;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import com.geststock.integration.CatalogProductSearch;

/**
 * Recherche de produits actifs de l'entreprise courante pour le rapprochement assisté (lot 3, étape G23).
 *
 * <p>La charge de chaque résultat est {@link ProductIntegrationEvents.Payload} — le <b>même format</b> que
 * les événements {@code PRODUCT} et la section {@code products} de l'instantané (G23-D1) : une seule
 * définition sérialisée par Jackson, sans seconde conversion.
 */
@Component
class ProductCatalogSearch implements CatalogProductSearch {

	private final ProductRepository repository;

	ProductCatalogSearch(ProductRepository repository) {
		this.repository = repository;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Le {@code pattern} est le motif ILIKE préparé par le contrôleur (entouré de {@code %}, ESCAPE
	 * {@code \}, déjà en minuscules). La requête applique {@code lower()} côté base sur sku et name.
	 */
	@Override
	public List<Object> search(String pattern, int limit) {
		return this.repository.searchActive(pattern, Limit.of(limit))
			.stream()
			.<Object>map(ProductIntegrationEvents.Payload::of)
			.toList();
	}

}
