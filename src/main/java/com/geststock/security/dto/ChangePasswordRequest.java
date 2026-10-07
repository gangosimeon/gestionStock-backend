package com.geststock.security.dto;

import jakarta.validation.constraints.NotBlank;

import io.swagger.v3.oas.annotations.media.Schema;

/** Changement de son propre mot de passe. */
@Schema(name = "ChangePasswordRequest")
public record ChangePasswordRequest(
		@NotBlank @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String currentPassword,
		@NotBlank @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String newPassword) {

	@Override
	public String toString() {
		return "ChangePasswordRequest[***]";
	}

}
