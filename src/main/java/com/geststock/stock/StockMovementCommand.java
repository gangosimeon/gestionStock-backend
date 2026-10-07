package com.geststock.stock;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Demande de mouvement adressée à {@link StockService} par un module (vente, achat,
 * inventaire…) ou par l'API manuelle. Non exposée en HTTP.
 *
 * @param warehouseId     dépôt (obligatoire)
 * @param productId       produit (obligatoire)
 * @param type            nature du mouvement
 * @param quantity        variation <b>signée</b> (entrée &gt; 0, sortie &lt; 0), cohérente avec le type
 * @param reason          motif libre, facultatif
 * @param sourceType      pièce d'origine
 * @param sourceId        identifiant de la pièce, facultatif
 * @param sourceReference numéro lisible de la pièce (ex. {@code PO-2026-0012}), facultatif
 * @param unitCost        coût unitaire ; {@code null} = prix d'achat courant du produit
 * @param correlationId   regroupe les mouvements d'une même opération, facultatif
 * @param reversalOfId    mouvement annulé (type {@code REVERSAL} uniquement)
 */
public record StockMovementCommand(
		UUID warehouseId,
		UUID productId,
		StockMovementType type,
		int quantity,
		String reason,
		StockSourceType sourceType,
		UUID sourceId,
		String sourceReference,
		BigDecimal unitCost,
		UUID correlationId,
		UUID reversalOfId) {

	public StockMovementCommand {
		Objects.requireNonNull(warehouseId, "warehouseId");
		Objects.requireNonNull(productId, "productId");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(sourceType, "sourceType");
	}

	/** Mouvement simple, sans pièce d'origine identifiée. */
	public static StockMovementCommand of(UUID warehouseId, UUID productId, StockMovementType type, int quantity,
			StockSourceType sourceType, String reason) {
		return new StockMovementCommand(warehouseId, productId, type, quantity, reason, sourceType, null, null, null,
				null, null);
	}

	/** Même mouvement rattaché à une pièce. */
	public StockMovementCommand withSource(UUID newSourceId, String newSourceReference) {
		return new StockMovementCommand(this.warehouseId, this.productId, this.type, this.quantity, this.reason,
				this.sourceType, newSourceId, newSourceReference, this.unitCost, this.correlationId, this.reversalOfId);
	}

	/** Même mouvement valorisé à un coût unitaire donné. */
	public StockMovementCommand withUnitCost(BigDecimal newUnitCost) {
		return new StockMovementCommand(this.warehouseId, this.productId, this.type, this.quantity, this.reason,
				this.sourceType, this.sourceId, this.sourceReference, newUnitCost, this.correlationId, this.reversalOfId);
	}

	/** Même mouvement rattaché à une opération regroupant plusieurs mouvements. */
	public StockMovementCommand withCorrelation(UUID newCorrelationId) {
		return new StockMovementCommand(this.warehouseId, this.productId, this.type, this.quantity, this.reason,
				this.sourceType, this.sourceId, this.sourceReference, this.unitCost, newCorrelationId, this.reversalOfId);
	}

	StockLevelId key() {
		return new StockLevelId(this.warehouseId, this.productId);
	}

}
