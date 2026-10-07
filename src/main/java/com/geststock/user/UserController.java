package com.geststock.user;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springdoc.core.annotations.ParameterObject;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.common.api.OkResponse;
import com.geststock.common.api.PageQuery;
import com.geststock.common.api.PageResponse;
import com.geststock.common.security.Permissions;
import com.geststock.common.security.RequiresPermission;
import com.geststock.exception.ApiError;
import com.geststock.user.dto.AssignRolesRequest;
import com.geststock.user.dto.CreateUserRequest;
import com.geststock.user.dto.ResetPasswordRequest;
import com.geststock.user.dto.SetUserActiveRequest;
import com.geststock.user.dto.UpdateUserRequest;
import com.geststock.user.dto.UserFilter;
import com.geststock.user.dto.UserResponse;

/**
 * Gestion des utilisateurs. Chemins, verbes et formats identiques au contrat du front
 * (écran Utilisateurs), complétés par la consultation unitaire et la réinitialisation du
 * mot de passe.
 */
@RestController
@RequestMapping("/api/users")
@RequiresPermission(Permissions.USER_MANAGE)
@Tag(name = "Utilisateurs", description = "Comptes, rôles et statut (permission user:manage)")
@ApiResponse(responseCode = "401", description = "Non authentifié",
		content = @Content(schema = @Schema(implementation = ApiError.class)))
@ApiResponse(responseCode = "403", description = "Permission user:manage requise",
		content = @Content(schema = @Schema(implementation = ApiError.class)))
class UserController {

	private final UserService service;

	UserController(UserService service) {
		this.service = service;
	}

	@GetMapping
	@Operation(summary = "Lister les utilisateurs",
			description = "Sans page ni size, renvoie toute la liste (compatibilité front, plafond 5 000). "
					+ "Tris : username, lastName, firstName, fullName, email, createdAt, lastLoginAt.")
	ResponseEntity<PageResponse<UserResponse>> list(@ParameterObject UserFilter filter,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
			@Parameter(description = "champ,asc|desc (répétable)") @RequestParam(required = false) List<String> sort) {
		PageQuery query = new PageQuery(page, size, sort);
		return PageResponse.toResponse(this.service.search(filter, query), query);
	}

	@GetMapping("/{id}")
	@Operation(summary = "Consulter un utilisateur")
	@ApiResponse(responseCode = "404", description = "Utilisateur introuvable",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	UserResponse get(@PathVariable UUID id) {
		return this.service.get(id);
	}

	@PostMapping
	@Operation(summary = "Créer un utilisateur")
	@ApiResponse(responseCode = "201", description = "Utilisateur créé")
	@ApiResponse(responseCode = "400", description = "Données invalides, rôle ou dépôt inconnu, mot de passe refusé",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@ApiResponse(responseCode = "409", description = "Identifiant ou email déjà utilisé",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
		UserResponse created = this.service.create(request);
		return ResponseEntity.created(URI.create("/api/users/" + created.id())).body(created);
	}

	@PutMapping("/{id}")
	@Operation(summary = "Modifier un utilisateur")
	@ApiResponse(responseCode = "409", description = "Identifiant ou email déjà utilisé, dernier administrateur",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	UserResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
		return this.service.update(id, request);
	}

	@PutMapping("/{id}/roles")
	@Operation(summary = "Attribuer les rôles d'un utilisateur",
			description = "Remplace ses rôles. Refusé (403 PRIVILEGE_ESCALATION) si un rôle ajouté accorde une "
					+ "permission que vous ne possédez pas.")
	@ApiResponse(responseCode = "409", description = "Retrait du rôle ADMIN au dernier administrateur",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	UserResponse assignRoles(@PathVariable UUID id, @Valid @RequestBody AssignRolesRequest request) {
		return this.service.assignRoles(id, request.roles());
	}

	@PatchMapping("/{id}/active")
	@Operation(summary = "Activer ou désactiver un compte")
	@ApiResponse(responseCode = "409", description = "Désactivation du dernier administrateur actif",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	UserResponse setActive(@PathVariable UUID id, @Valid @RequestBody SetUserActiveRequest request) {
		return this.service.setActive(id, request.isActive());
	}

	@DeleteMapping("/{id}")
	@Operation(summary = "Supprimer un utilisateur (désactivation)",
			description = "Le compte est désactivé, jamais effacé : l'historique conserve son auteur.")
	OkResponse delete(@PathVariable UUID id) {
		this.service.delete(id);
		return OkResponse.success();
	}

	@PutMapping("/{id}/password")
	@Operation(summary = "Attribuer un mot de passe",
			description = "Mot de passe temporaire par défaut : l'utilisateur devra le changer à sa prochaine connexion.")
	@ApiResponse(responseCode = "204", description = "Mot de passe enregistré")
	@ApiResponse(responseCode = "400", description = "Mot de passe refusé par la politique de sécurité",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	ResponseEntity<Void> resetPassword(@PathVariable UUID id, @Valid @RequestBody ResetPasswordRequest request) {
		this.service.resetPassword(id, request);
		return ResponseEntity.noContent().build();
	}

}
