package com.geststock.security.dto;

import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/** Déconnexion : le refresh token de la session à fermer (facultatif). */
@Schema(name = "LogoutRequest")
public record LogoutRequest(@Size(max = 128) String refreshToken) {

	@Override
	public String toString() {
		return "LogoutRequest[refreshToken=***]";
	}

}
