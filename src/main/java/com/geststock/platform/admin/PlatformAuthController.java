package com.geststock.platform.admin;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.exception.ApiError;
import com.geststock.platform.admin.dto.PlatformAuthDtos.AdminResponse;
import com.geststock.platform.admin.dto.PlatformAuthDtos.AuthResponse;
import com.geststock.platform.admin.dto.PlatformAuthDtos.ChangePasswordRequest;
import com.geststock.platform.admin.dto.PlatformAuthDtos.LoginRequest;
import com.geststock.platform.admin.dto.PlatformAuthDtos.RefreshRequest;

/**
 * Authentification Super Admin, distincte de celle des entreprises : autres comptes (base plateforme),
 * autre type de jeton, routes {@code /api/platform/**} uniquement. Aucune inscription publique.
 */
@RestController
@RequestMapping("/api/platform/auth")
@Tag(name = "Plateforme — authentification", description = "Connexion du Super Admin GestStock")
class PlatformAuthController {

	private final PlatformAuthService service;

	PlatformAuthController(PlatformAuthService service) {
		this.service = service;
	}

	@PostMapping("/login")
	@SecurityRequirements
	@Operation(summary = "Connexion Super Admin")
	@ApiResponse(responseCode = "200", description = "Connecté")
	@ApiResponse(responseCode = "401", description = "INVALID_CREDENTIALS, ACCOUNT_LOCKED ou ACCOUNT_DISABLED",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	AuthResponse login(@Valid @RequestBody LoginRequest request) {
		return this.service.login(request.username(), request.password());
	}

	@PostMapping("/refresh")
	@SecurityRequirements
	@Operation(summary = "Renouveler la session Super Admin")
	@ApiResponse(responseCode = "200", description = "OK")
	@ApiResponse(responseCode = "401", description = "INVALID_REFRESH_TOKEN",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
		return this.service.refresh(request.refreshToken());
	}

	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@SecurityRequirements
	@Operation(summary = "Se déconnecter", description = "Révoque la session. Toujours 204.")
	@ApiResponse(responseCode = "204", description = "Session fermée")
	ResponseEntity<Void> logout(@Valid @RequestBody(required = false) RefreshRequest request) {
		if (request != null) {
			this.service.logout(request.refreshToken());
		}
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/me")
	@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'SUPER_ADMIN_PASSWORD_CHANGE')")
	@Operation(summary = "Super Admin connecté")
	@ApiResponse(responseCode = "200", description = "OK")
	AdminResponse me(@AuthenticationPrincipal PlatformAuthentication.Principal admin) {
		return this.service.me(admin.id());
	}

	@PostMapping("/change-password")
	@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'SUPER_ADMIN_PASSWORD_CHANGE')")
	@Operation(summary = "Changer son mot de passe",
			description = "12 caractères au moins, minuscule, majuscule et chiffre. Ferme toutes les sessions puis en ouvre une nouvelle.")
	@ApiResponse(responseCode = "200", description = "OK")
	@ApiResponse(responseCode = "400", description = "CURRENT_PASSWORD_INVALID ou PASSWORD_POLICY_VIOLATION",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	AuthResponse changePassword(@AuthenticationPrincipal PlatformAuthentication.Principal admin,
			@Valid @RequestBody ChangePasswordRequest request) {
		return this.service.changePassword(admin.id(), request.currentPassword(), request.newPassword());
	}

}
