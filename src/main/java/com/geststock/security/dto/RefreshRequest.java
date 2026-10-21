package com.geststock.security.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/** Renouvellement de session (contrat du front : {@code {refreshToken}}). */
@Schema(name = "RefreshRequest")
public record RefreshRequest(@NotBlank @Size(max = 128) String refreshToken,
		@Size(max = 40) @Schema(description = "Code de l'entreprise de la session (champ company de la connexion)") String company) {

	@Override
	public String toString() {
		return "RefreshRequest[refreshToken=***]";
	}

}
