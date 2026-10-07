package com.geststock.role.dto;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Rôle et ses permissions effectives.
 *
 * @param builtIn     rôle système (non supprimable)
 * @param editable    permissions modifiables ({@code false} pour ADMIN, qui les détient toutes)
 * @param permissions codes {@code domaine:action}, triés
 * @param userCount   nombre d'utilisateurs portant ce rôle
 */
@Schema(name = "Role")
public record RoleResponse(
		UUID id,
		@Schema(example = "CAISSIER") String code,
		@Schema(example = "Caissier") String label,
		String description,
		boolean builtIn,
		boolean editable,
		List<String> permissions,
		long userCount) {
}
