package com.geststock.role.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/** Modification d'un rôle : libellé, description et liste complète de ses permissions. */
@Schema(name = "UpdateRoleRequest")
public record UpdateRoleRequest(
		@NotBlank @Size(max = 100) String label,
		@Size(max = 255) String description,
		@NotNull List<@NotBlank String> permissions) {
}
