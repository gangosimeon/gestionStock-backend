package com.geststock.product;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import com.geststock.integration.CatalogProductIds;
import com.geststock.integration.SnapshotItem;
import com.geststock.integration.SnapshotSection;
import com.geststock.integration.SnapshotSource;

/**
 * Section {@code products} de l'instantané d'intégration : archivés compris, par identifiant croissant, au format
 * des événements {@code PRODUCT} ({@link ProductIntegrationEvents.Payload}, même mappeur) ; et identifiants des
 * produits, clé des sections {@code productImages} et {@code availability}.
 */
@Component
class ProductSnapshotSource implements SnapshotSource, CatalogProductIds {

	private final ProductRepository repository;

	ProductSnapshotSource(ProductRepository repository) {
		this.repository = repository;
	}

	@Override
	public SnapshotSection section() {
		return SnapshotSection.PRODUCTS;
	}

	@Override
	public List<SnapshotItem> after(UUID afterId, int limit) {
		return this.repository.findAfter(afterId, Limit.of(limit)).stream()
			.map(product -> new SnapshotItem(product.getId(), ProductIntegrationEvents.Payload.of(product)))
			.toList();
	}

	@Override
	public List<UUID> idsAfter(UUID afterId, int limit) {
		return this.repository.findIdsAfter(afterId, Limit.of(limit));
	}

}
