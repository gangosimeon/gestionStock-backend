package com.geststock.integration;

import java.util.Objects;
import java.util.UUID;

/**
 * Identifiant opaque de la liaison qui lit l'instantané, fourni par l'appelant. Les liaisons n'existent pas encore
 * (lot 2) : seul son identifiant sert, pour lier un {@code pageToken} à la liaison qui l'a obtenu.
 */
public record LinkRef(UUID id) {

	public LinkRef {
		Objects.requireNonNull(id, "id");
	}

}
