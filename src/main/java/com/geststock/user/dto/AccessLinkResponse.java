package com.geststock.user.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Lien d'accès émis pour un utilisateur : jeton à transmettre <b>une seule fois</b> à l'intéressé
 * (le serveur n'en garde que le haché). Le front construit l'URL {@code /activate?company=…&token=…}.
 *
 * @param token     jeton à usage unique
 * @param expiresAt fin de validité
 */
@Schema(name = "AccessLink")
public record AccessLinkResponse(String token, Instant expiresAt) {

	@Override
	public String toString() {
		return "AccessLinkResponse[token=***, expiresAt=" + this.expiresAt + "]";
	}

}
