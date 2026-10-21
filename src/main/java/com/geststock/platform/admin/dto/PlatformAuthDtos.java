package com.geststock.platform.admin.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/** Contrats de l'authentification Super Admin ({@code /api/platform/auth}). */
public final class PlatformAuthDtos {

	private PlatformAuthDtos() {
	}

	@Schema(name = "PlatformLoginRequest")
	public record LoginRequest(@NotBlank @Size(max = 50) String username,
			@NotBlank @Size(max = 256) @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String password) {

		@Override
		public String toString() {
			return "LoginRequest[username=" + this.username + ", password=***]";
		}

	}

	@Schema(name = "PlatformRefreshRequest")
	public record RefreshRequest(@NotBlank @Size(max = 128) String refreshToken) {

		@Override
		public String toString() {
			return "RefreshRequest[refreshToken=***]";
		}

	}

	@Schema(name = "PlatformChangePasswordRequest")
	public record ChangePasswordRequest(@NotBlank @Size(max = 256) String currentPassword,
			@NotBlank @Size(max = 256) String newPassword) {

		@Override
		public String toString() {
			return "ChangePasswordRequest[***]";
		}

	}

	@Schema(name = "PlatformAdmin")
	public record AdminResponse(UUID id, String username, String fullName, String email,
			@Schema(description = "Mot de passe initial à changer avant toute autre opération") boolean mustChangePassword) {
	}

	@Schema(name = "PlatformAuthResponse")
	public record AuthResponse(String accessToken, String refreshToken, long expiresIn, String tokenType, AdminResponse admin) {

		@Override
		public String toString() {
			return "AuthResponse[accessToken=***, refreshToken=***, expiresIn=" + this.expiresIn + "]";
		}

	}

}
