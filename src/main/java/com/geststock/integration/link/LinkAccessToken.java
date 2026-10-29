package com.geststock.integration.link;

import java.time.Instant;

/**
 * Jeton d'accès émis pour une liaison ({@code access_token} de {@code /oauth/token}).
 *
 * @param value            jeton compact (JWS) ; jamais journalisé
 * @param expiresAt        expiration
 * @param expiresInSeconds durée de vie ({@code expires_in}), 900 secondes
 */
public record LinkAccessToken(String value, Instant expiresAt, long expiresInSeconds) {

	@Override
	public String toString() {
		return "LinkAccessToken[value=***, expiresAt=" + this.expiresAt + "]";
	}

}
