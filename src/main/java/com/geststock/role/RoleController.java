package com.geststock.role;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.audit.Audited;
import com.geststock.common.api.OkResponse;
import com.geststock.common.api.PageResponse;
import com.geststock.common.security.Permissions;
import com.geststock.common.security.RequiresPermission;
import com.geststock.exception.ApiError;
import com.geststock.role.dto.CreateRoleRequest;
import com.geststock.role.dto.RoleResponse;
import com.geststock.role.dto.UpdateRoleRequest;

/**
 * Gestion des rôles et de leurs permissions. La lecture est aussi ouverte aux
 * gestionnaires d'utilisateurs (choix des rôles à attribuer).
 */
@RestController
@RequestMapping("/api/roles")
@Tag(name = "Rôles et permissions", description = "Rôles et matrice rôle → permissions (permission role:manage)")
@ApiResponse(responseCode = "403", description = "Permission manquante ou escalade de privilèges",
		content = @Content(schema = @Schema(implementation = ApiError.class)))
class RoleController {

	private final RoleService service;

	RoleController(RoleService service) {
		this.service = service;
	}

	@GetMapping
	@PreAuthorize("hasAnyAuthority('" + Permissions.ROLE_MANAGE + "', '" + Permissions.USER_MANAGE + "')")
	@Operation(summary = "Lister les rôles et leurs permissions")
	PageResponse<RoleResponse> list() {
		return PageResponse.unpaged(this.service.list());
	}

	@GetMapping("/{id}")
	@PreAuthorize("hasAnyAuthority('" + Permissions.ROLE_MANAGE + "', '" + Permissions.USER_MANAGE + "')")
	@Operation(summary = "Consulter un rôle")
	RoleResponse get(@PathVariable UUID id) {
		return this.service.get(id);
	}

	@PostMapping
	@Audited(action = "CREATE", entityType = "ROLE", idParam = "")
	@RequiresPermission(Permissions.ROLE_MANAGE)
	@Operation(summary = "Créer un rôle personnalisé")
	@ApiResponse(responseCode = "201", description = "Rôle créé")
	@ApiResponse(responseCode = "409", description = "Code déjà utilisé",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	ResponseEntity<RoleResponse> create(@Valid @RequestBody CreateRoleRequest request) {
		RoleResponse created = this.service.create(request);
		return ResponseEntity.created(URI.create("/api/roles/" + created.id())).body(created);
	}

	@PutMapping("/{id}")
	@Audited(action = "UPDATE", entityType = "ROLE")
	@RequiresPermission(Permissions.ROLE_MANAGE)
	@Operation(summary = "Modifier un rôle et ses permissions", description = "Le rôle ADMIN n'est pas modifiable.")
	@ApiResponse(responseCode = "409", description = "Rôle ADMIN",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	RoleResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateRoleRequest request) {
		return this.service.update(id, request);
	}

	@DeleteMapping("/{id}")
	@Audited(action = "DELETE", entityType = "ROLE")
	@RequiresPermission(Permissions.ROLE_MANAGE)
	@Operation(summary = "Supprimer un rôle personnalisé", description = "Refusé pour un rôle système ou encore attribué.")
	@ApiResponse(responseCode = "409", description = "Rôle système ou attribué",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	OkResponse delete(@PathVariable UUID id) {
		this.service.delete(id);
		return OkResponse.success();
	}

}
