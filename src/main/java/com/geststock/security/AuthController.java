package com.geststock.security;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.audit.AuditModule;
import com.geststock.audit.Audited;
import com.geststock.exception.ApiError;
import com.geststock.security.dto.AuthResponse;
import com.geststock.security.dto.ChangePasswordRequest;
import com.geststock.security.dto.ForgotPasswordRequest;
import com.geststock.security.dto.LoginRequest;
import com.geststock.security.dto.LogoutRequest;
import com.geststock.security.dto.MeResponse;
import com.geststock.security.dto.RefreshRequest;

/**
 * Authentification. Chemins et formats identiques au contrat du front
 * ({@code auth.service.ts}, {@code jwt.interceptor.ts}).
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentification", description = "Connexion, session, utilisateur courant")
class AuthController {

	private static final Logger log = LoggerFactory.getLogger(AuthController.class);

	private final AuthService service;

	AuthController(AuthService service) {
		this.service = service;
	}

	@PostMapping("/login")
	@SecurityRequirements
	@Operation(summary = "Se connecter", description = "Renvoie un jeton d'accès (15 min) et un refresh token à usage unique.")
	@ApiResponse(responseCode = "200", description = "Connecté")
	@ApiResponse(responseCode = "401", description = "INVALID_CREDENTIALS, ACCOUNT_LOCKED ou ACCOUNT_DISABLED",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
		return this.service.login(request.username(), request.password(), ClientInfo.from(http));
	}

	@PostMapping("/refresh")
	@SecurityRequirements
	@Operation(summary = "Renouveler la session",
			description = "Le refresh token présenté est révoqué et remplacé. Le réutiliser ensuite ferme toutes les sessions de la même connexion.")
	@ApiResponse(responseCode = "401", description = "INVALID_REFRESH_TOKEN",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@ApiResponse(responseCode = "200", description = "OK")
	AuthResponse refresh(@Valid @RequestBody RefreshRequest request, HttpServletRequest http) {
		return this.service.refresh(request.refreshToken(), ClientInfo.from(http));
	}

	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@SecurityRequirements
	@Operation(summary = "Se déconnecter", description = "Révoque le refresh token fourni. Toujours 204.")
	@ApiResponse(responseCode = "204", description = "Session fermée")
	ResponseEntity<Void> logout(@Valid @RequestBody(required = false) LogoutRequest request) {
		this.service.logout(request != null ? request.refreshToken() : null);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/me")
	@PreAuthorize("isAuthenticated()")
	@Operation(summary = "Utilisateur connecté et permissions effectives")
	@ApiResponse(responseCode = "200", description = "OK")
	MeResponse me(@AuthenticationPrincipal CurrentUser user) {
		return this.service.me(user);
	}

	@PostMapping("/change-password")
	@Audited(action = "PASSWORD_CHANGE", entityType = "USER", idParam = "", module = AuditModule.AUTH)
	@PreAuthorize("isAuthenticated()")
	@Operation(summary = "Changer son mot de passe", description = "Ferme toutes les sessions puis renvoie une nouvelle session.")
	@ApiResponse(responseCode = "400", description = "CURRENT_PASSWORD_INVALID ou PASSWORD_POLICY_VIOLATION",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@ApiResponse(responseCode = "200", description = "OK")
	AuthResponse changePassword(@AuthenticationPrincipal CurrentUser user, @Valid @RequestBody ChangePasswordRequest request,
			HttpServletRequest http) {
		return this.service.changePassword(user, request.currentPassword(), request.newPassword(), ClientInfo.from(http));
	}

	/**
	 * Réponse identique que l'email existe ou non (aucune énumération des comptes). L'envoi
	 * d'un lien de réinitialisation n'est pas encore disponible : un administrateur attribue
	 * un nouveau mot de passe ({@code PUT /api/users/{id}/password}).
	 */
	@PostMapping("/forgot-password")
	@SecurityRequirements
	@Operation(summary = "Mot de passe oublié", description = "Toujours 202, que le compte existe ou non.")
	@ApiResponse(responseCode = "202", description = "Demande prise en compte (réponse identique que le compte existe ou non)")
	ResponseEntity<Map<String, String>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
		log.info("Demande de réinitialisation de mot de passe reçue.");
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("message",
				"Si un compte correspond à cette adresse, un administrateur vous communiquera un nouveau mot de passe."));
	}

}
