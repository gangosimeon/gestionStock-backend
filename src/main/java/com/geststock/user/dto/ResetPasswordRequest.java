package com.geststock.user.dto;

import jakarta.validation.constraints.NotBlank;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Attribution d'un mot de passe par un administrateur. Le mot de passe est vérifié par la
 * politique de sécurité, haché, puis oublié : il n'est jamais renvoyé ni journalisé.
 *
 * @param password  nouveau mot de passe
 * @param temporary {@code true} (défaut) : l'utilisateur devra le changer à sa prochaine connexion
 */
@Schema(name = "ResetPasswordRequest")
public record ResetPasswordRequest(
		@NotBlank @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String password,
		Boolean temporary) {

	@Override
	public String toString() {
		return "ResetPasswordRequest[password=***, temporary=" + this.temporary + "]";
	}

}
