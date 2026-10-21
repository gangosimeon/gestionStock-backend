package com.geststock.security.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Définition du mot de passe par lien d'accès (activation du compte ou réinitialisation).
 *
 * @param company     code de l'entreprise (dans le lien)
 * @param token       jeton du lien
 * @param newPassword mot de passe choisi
 */
@Schema(name = "ActivateAccountRequest")
public record ActivateAccountRequest(@Size(max = 40) String company, @NotBlank @Size(max = 128) String token,
		@NotBlank @Size(max = 256) @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String newPassword) {

	@Override
	public String toString() {
		return "ActivateAccountRequest[company=" + this.company + ", token=***, newPassword=***]";
	}

}
