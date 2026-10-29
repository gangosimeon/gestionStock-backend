package com.geststock.category;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import com.geststock.integration.SnapshotItem;
import com.geststock.integration.SnapshotSection;
import com.geststock.integration.SnapshotSource;

/**
 * Section {@code categories} de l'instantané d'intégration : archivées comprises, par identifiant croissant, au
 * format des événements {@code CATEGORY} ({@link CategoryIntegrationEvents.Payload}, même mappeur).
 */
@Component
class CategorySnapshotSource implements SnapshotSource {

	private final CategoryRepository repository;

	CategorySnapshotSource(CategoryRepository repository) {
		this.repository = repository;
	}

	@Override
	public SnapshotSection section() {
		return SnapshotSection.CATEGORIES;
	}

	@Override
	public List<SnapshotItem> after(UUID afterId, int limit) {
		return this.repository.findAfter(afterId, Limit.of(limit)).stream()
			.map(category -> new SnapshotItem(category.getId(), CategoryIntegrationEvents.Payload.of(category)))
			.toList();
	}

}
