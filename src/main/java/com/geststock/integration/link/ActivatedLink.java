package com.geststock.integration.link;

/**
 * Résultat de l'activation d'une liaison : la liaison, et son secret en clair, renvoyé cette seule fois. Seule
 * l'empreinte SHA-256 du secret est conservée ; le secret n'apparaît dans aucun journal ni {@code toString}.
 *
 * @param link   liaison activée
 * @param secret secret de liaison (256 bits, base64url sans remplissage)
 */
public record ActivatedLink(Link link, String secret) {

	@Override
	public String toString() {
		return "ActivatedLink[link=" + this.link.id() + ", secret=***]";
	}

}
