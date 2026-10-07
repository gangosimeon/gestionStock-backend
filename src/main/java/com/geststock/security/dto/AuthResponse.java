package com.geststock.security.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Réponse de connexion et de renouvellement (contrat du front :
 * {@code {accessToken, refreshToken, expiresIn, user}}).
 *
 * @param accessToken  JWT à envoyer dans {@code Authorization: Bearer ...}
 * @param refreshToken jeton opaque à usage unique, pour {@code POST /api/auth/refresh}
 * @param expiresIn    durée de validité du jeton d'accès, en secondes
 * @param tokenType    toujours {@code Bearer}
 * @param user         utilisateur connecté et ses permissions
 */
@Schema(name = "AuthResponse")
public record AuthResponse(String accessToken, String refreshToken, long expiresIn, String tokenType, MeResponse user) {

	public AuthResponse(String accessToken, String refreshToken, long expiresIn, MeResponse user) {
		this(accessToken, refreshToken, expiresIn, "Bearer", user);
	}

	@Override
	public String toString() {
		return "AuthResponse[accessToken=***, refreshToken=***, expiresIn=" + this.expiresIn + "]";
	}

}
