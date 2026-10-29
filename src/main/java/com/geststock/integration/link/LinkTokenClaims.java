package com.geststock.integration.link;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Contenu vérifié d'un jeton de liaison. L'entreprise n'y figure pas : elle est relue depuis la liaison à chaque
 * appel (v13-D6), comme le statut de la liaison (étape G7).
 *
 * @param linkId    liaison ({@code sub})
 * @param clientId  client JeCréeMaBoutique ({@code client_id})
 * @param scopes    portées accordées ({@code scope})
 * @param tokenId   identifiant du jeton ({@code jti})
 * @param issuedAt  émission ({@code iat})
 * @param expiresAt expiration ({@code exp})
 * @param keyId     clé de signature ({@code kid})
 */
public record LinkTokenClaims(UUID linkId, String clientId, Set<String> scopes, String tokenId, Instant issuedAt,
		Instant expiresAt, String keyId) {

	public LinkTokenClaims {
		scopes = Set.copyOf(scopes);
	}

}
