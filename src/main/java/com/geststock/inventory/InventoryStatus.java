package com.geststock.inventory;

/**
 * Étapes d'un inventaire. La base impose les mêmes transitions ({@code fn_inventory_session_guard}).
 *
 * <pre>
 * DRAFT ──démarrer──► IN_PROGRESS ──1er comptage──► COUNTING ──soumettre──► VALIDATION ──valider──► COMPLETED
 *                                                       ▲                       │
 *                                                       └──────renvoyer─────────┘
 * (annulation possible à toute étape avant COMPLETED → CANCELLED)
 * </pre>
 */
public enum InventoryStatus {

	/** Brouillon : périmètre (produits) modifiable. */
	DRAFT,

	/** En cours : périmètre figé, comptage ouvert. */
	IN_PROGRESS,

	/** Comptage : au moins une quantité saisie. */
	COUNTING,

	/** En validation : comptage clos, en attente d'un valideur. */
	VALIDATION,

	/** Terminé : écarts appliqués au stock. Définitif. */
	COMPLETED,

	/** Annulé avant validation : aucun effet sur le stock. Définitif. */
	CANCELLED;

	public boolean isFinal() {
		return this == COMPLETED || this == CANCELLED;
	}

	public boolean acceptsCounts() {
		return this == IN_PROGRESS || this == COUNTING;
	}

}
