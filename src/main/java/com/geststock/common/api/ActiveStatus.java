package com.geststock.common.api;

/**
 * Filtre sur l'état actif / désactivé d'un référentiel ({@code ?status=}). Par défaut
 * {@link #ACTIVE} : un élément « supprimé » (désactivé) disparaît des listes, comme le front
 * l'attend, tout en restant consultable et réactivable.
 */
public enum ActiveStatus {

	ACTIVE, INACTIVE, ALL;

	/** Valeur de la colonne {@code active} à filtrer, ou {@code null} pour tout. */
	public Boolean asActiveFlag() {
		return switch (this) {
			case ACTIVE -> Boolean.TRUE;
			case INACTIVE -> Boolean.FALSE;
			case ALL -> null;
		};
	}

}
