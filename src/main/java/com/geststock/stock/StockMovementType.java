package com.geststock.stock;

/**
 * Nature d'un mouvement de stock (contrainte {@code ck_stock_movement_type}). Le sens est
 * imposé par le type, en base ({@code ck_stock_movement_direction}) comme ici.
 */
public enum StockMovementType {

	/** Reprise du stock existant à la mise en service (import). */
	OPENING(Direction.IN, LegacyReason.SUPPLY),

	/** Réception de marchandise (commande fournisseur ou saisie manuelle d'une entrée). */
	RECEIPT(Direction.IN, LegacyReason.SUPPLY),

	/** Retour d'un article par un client : remise en stock. */
	CUSTOMER_RETURN(Direction.IN, LegacyReason.ADJUSTMENT),

	/** Annulation d'une vente : les articles reviennent en stock. */
	SALE_CANCELLATION(Direction.IN, LegacyReason.SALE),

	/** Arrivée d'un transfert depuis un autre dépôt. */
	TRANSFER_IN(Direction.IN, LegacyReason.ADJUSTMENT),

	/** Vente. */
	SALE(Direction.OUT, LegacyReason.SALE),

	/** Retour de marchandise au fournisseur. */
	SUPPLIER_RETURN(Direction.OUT, LegacyReason.ADJUSTMENT),

	/** Perte, casse, vol. */
	LOSS(Direction.OUT, LegacyReason.LOSS),

	/** Départ d'un transfert vers un autre dépôt. */
	TRANSFER_OUT(Direction.OUT, LegacyReason.ADJUSTMENT),

	/** Ajustement manuel, positif ou négatif. */
	ADJUSTMENT(Direction.BOTH, LegacyReason.ADJUSTMENT),

	/** Écart d'inventaire validé (quantité comptée − quantité théorique). */
	INVENTORY(Direction.BOTH, LegacyReason.ADJUSTMENT),

	/** Annulation d'un mouvement manuel : quantité opposée. */
	REVERSAL(Direction.BOTH, LegacyReason.ADJUSTMENT);

	/** Sens autorisé de la quantité. */
	public enum Direction {

		IN, OUT, BOTH

	}

	/** Motif du contrat actuel du front ({@code StockMovementReason}). */
	public enum LegacyReason {

		SUPPLY, SALE, LOSS, ADJUSTMENT

	}

	private final Direction direction;

	private final LegacyReason legacyReason;

	StockMovementType(Direction direction, LegacyReason legacyReason) {
		this.direction = direction;
		this.legacyReason = legacyReason;
	}

	public Direction direction() {
		return this.direction;
	}

	public LegacyReason legacyReason() {
		return this.legacyReason;
	}

	/** Quantité signée compatible avec le type (jamais nulle). */
	public boolean accepts(int quantity) {
		return switch (this.direction) {
			case IN -> quantity > 0;
			case OUT -> quantity < 0;
			case BOTH -> quantity != 0;
		};
	}

}
