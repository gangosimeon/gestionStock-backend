package com.geststock.category;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.geststock.integration.IntegrationEventType;
import com.geststock.integration.IntegrationEvents;
import com.geststock.integration.IntegrationOperation;

/**
 * Événements {@code CATEGORY} du contrat {@code integration/v1} (lot 1, étape 4a). Un événement n'est publié
 * que si un champ exposé par le schéma {@code Category} a changé ; catégorie désactivée : {@code ARCHIVE}
 * (archivée côté boutique, plan § 5.3), active : {@code UPSERT}. Appelé après {@code saveAndFlush}, dans la
 * transaction du service : la version de la charge est celle de l'événement (règle R1).
 */
@Component
class CategoryIntegrationEvents {

	private final IntegrationEvents events;

	CategoryIntegrationEvents(IntegrationEvents events) {
		this.events = events;
	}

	/**
	 * @param before état exposé avant la modification ({@link Exposed#of}) ; {@code null} pour une création
	 * @param after  catégorie enregistrée et flushée
	 */
	void publishIfChanged(Exposed before, Category after) {
		if (Exposed.of(after).equals(before)) {
			return;
		}
		this.events.publish(IntegrationEventType.CATEGORY,
				after.isActive() ? IntegrationOperation.UPSERT : IntegrationOperation.ARCHIVE, after, Payload.of(after));
	}

	/** Champs du schéma {@code Category} hors identifiant et version : base de la comparaison. */
	record Exposed(String name, String description, boolean active) {

		static Exposed of(Category category) {
			return new Exposed(category.getName(), category.getDescription(), category.isActive());
		}

	}

	/** Charge : schéma {@code Category} du contrat, exactement. */
	record Payload(UUID id, String name, String description, boolean active, long version) {

		static Payload of(Category category) {
			return new Payload(category.getId(), category.getName(), category.getDescription(), category.isActive(),
					category.getVersion());
		}

	}

}
