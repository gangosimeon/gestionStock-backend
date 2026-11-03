package com.geststock.integration;

/** Raison de l'arrêt d'une purge ou d'un séquencement contrôlés par un passage ({@link PassControl}). */
public enum PassStop {

	/** Travail terminé : rien d'autre à faire dans les limites du passage. */
	COMPLETE,
	/** Budget ou limite de lots atteint alors qu'il reste du travail : reprise au passage suivant (résultat partiel). */
	LIMIT_REACHED,
	/** Bail perdu ou échu : aucun nouveau lot ; jamais confondu avec un budget épuisé. */
	LEASE_LOST

}
