package com.geststock.security.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/** Identifiants de connexion (contrat du front : {@code {username, password}}). */
@Schema(name = "LoginRequest")
public record LoginRequest(
		@NotBlank @Size(max = 254) @Schema(example = "admin") String username,
		@NotBlank @Size(max = 256) @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String password) {

	@Override
	public String toString() {
		return "LoginRequest[username=" + this.username + ", password=***]";
	}

}
