package com.geststock.user.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import io.swagger.v3.oas.annotations.media.Schema;

/** Rôles d'un utilisateur (remplacent les rôles actuels). */
@Schema(name = "AssignRolesRequest")
public record AssignRolesRequest(
		@NotEmpty(message = "au moins un rôle est requis") @Schema(example = "[\"CAISSIER\"]") List<@NotBlank String> roles) {
}
