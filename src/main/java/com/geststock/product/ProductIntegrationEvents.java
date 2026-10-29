package com.geststock.product;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.geststock.integration.IntegrationEventType;
import com.geststock.integration.IntegrationEvents;
import com.geststock.integration.IntegrationOperation;

/**
 * Événements {@code PRODUCT} du contrat {@code integration/v1} (lot 1, étape 4a). Un événement n'est publié que
 * si un champ exposé par le schéma {@code Product} a changé : prix d'achat, prix de gros, code-barres, marque,
 * fournisseur et seuils de stock ne sont jamais exposés. Produit désactivé : {@code ARCHIVE} (archivé côté
 * boutique, plan § 5.3), actif : {@code UPSERT}. Appelé après {@code saveAndFlush}, dans la transaction du
 * service : la version de la charge est celle de l'événement (règle R1).
 *
 * <p>Champs du schéma non remplis : {@code currency} (hypothèse, décision C8 ouverte) et {@code imageSha256}
 * (images, étape 4b).
 */
@Component
class ProductIntegrationEvents {

	private final IntegrationEvents events;

	ProductIntegrationEvents(IntegrationEvents events) {
		this.events = events;
	}

	/**
	 * @param before charge avant la modification, version neutralisée ({@code Payload.of(…).withoutVersion()}) ;
	 *               {@code null} pour une création
	 * @param after  produit enregistré et flushé
	 */
	void publishIfChanged(Payload before, Product after) {
		Payload now = Payload.of(after);
		if (now.withoutVersion().equals(before)) {
			return;
		}
		this.events.publish(IntegrationEventType.PRODUCT,
				after.isActive() ? IntegrationOperation.UPSERT : IntegrationOperation.ARCHIVE, after, now);
	}

	/**
	 * Charge : schéma {@code Product} du contrat, sans {@code currency} ni {@code imageSha256}. Seule source des
	 * champs exposés : la détection « rien n'a changé » compare cette sortie, version neutralisée.
	 */
	record Payload(UUID id, String sku, String name, String description, UUID categoryId, ProductUnit unit,
			String referencePrice, boolean active, long version) {

		static Payload of(Product product) {
			return new Payload(product.getId(), product.getSku(), product.getName(), product.getDescription(),
					product.getCategory().getId(), product.getUnit(), decimal(product.getRetailPrice()), product.isActive(),
					product.getVersion());
		}

		/** Même charge, version à 0 : base de la comparaison avant/après. */
		Payload withoutVersion() {
			return new Payload(this.id, this.sku, this.name, this.description, this.categoryId, this.unit,
					this.referencePrice, this.active, 0L);
		}

		/** Décimal en chaîne, deux décimales (contrat : {@code "6000.00"}), comme la colonne numeric(14,2). */
		private static String decimal(BigDecimal value) {
			return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
		}

	}

}
