package com.geststock.integration;

/**
 * Contrôle d'un passage de maintenance sur le flux de l'entreprise courante (lot 3, G20b ; décision B, G20-D12),
 * consulté par la purge et le séquencement <b>avant chaque lot</b>, jamais pendant : un lot commencé est mené à son
 * terme et validé, l'arrêt a lieu au contrôle suivant.
 *
 * <ul>
 * <li>{@link #leaseHeld()} faux : bail perdu ou échu ; aucun nouveau lot, pas même le premier.</li>
 * <li>{@link #timeLeft()} faux : budget épuisé ; le premier lot a quand même lieu (v17-D16), puis arrêt propre.</li>
 * </ul>
 * La sécurité des données ne dépend pas de ce contrôle : chaque lot de purge reste borné par le plafond {@code P} de
 * son passage et par le repère relu sous verrou (G17).
 */
public interface PassControl {

	/** Aucune limite : bail réputé tenu, budget illimité (appels hors maintenance et tests). */
	PassControl UNLIMITED = new PassControl() {

		@Override
		public boolean leaseHeld() {
			return true;
		}

		@Override
		public boolean timeLeft() {
			return true;
		}

	};

	/** Le passage tient toujours son bail, non échu. */
	boolean leaseHeld();

	/** Il reste du budget pour un lot de plus. */
	boolean timeLeft();

}
