package com.geststock.integration.link;

import java.time.Instant;

/**
 * Résultat d'une rotation du secret de liaison (lot 2, étape G14 ; v14-D6) : le nouveau secret en clair, renvoyé cette
 * seule fois ; seule son empreinte SHA-256 est conservée (secret en attente). Le secret n'apparaît dans aucun journal
 * ni {@code toString}.
 *
 * @param link                     liaison
 * @param secret                   nouveau secret (256 bits, base64url sans remplissage)
 * @param rotatedAt                émission du nouveau secret ({@code pending_secret_created_at})
 * @param previousSecretValidUntil limite de validité de l'ancien secret (24 h après l'émission ; plus tôt dès le
 *                                 premier usage du nouveau)
 */
public record RotatedSecret(Link link, String secret, Instant rotatedAt, Instant previousSecretValidUntil) {

	@Override
	public String toString() {
		return "RotatedSecret[link=" + this.link.id() + ", secret=***, rotatedAt=" + this.rotatedAt + "]";
	}

}
