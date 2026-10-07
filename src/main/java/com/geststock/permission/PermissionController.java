package com.geststock.permission;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.common.api.PageResponse;
import com.geststock.common.security.Permissions;
import com.geststock.permission.dto.PermissionResponse;

/** Catalogue des permissions, en lecture (écrans de gestion des rôles et des utilisateurs). */
@RestController
@RequestMapping("/api/permissions")
@Tag(name = "Rôles et permissions")
class PermissionController {

	private final PermissionService service;

	PermissionController(PermissionService service) {
		this.service = service;
	}

	@GetMapping
	@PreAuthorize("hasAnyAuthority('" + Permissions.ROLE_MANAGE + "', '" + Permissions.USER_MANAGE + "')")
	@Operation(summary = "Catalogue des permissions", description = "Permission role:manage ou user:manage.")
	PageResponse<PermissionResponse> list() {
		return PageResponse.unpaged(this.service.list());
	}

}
