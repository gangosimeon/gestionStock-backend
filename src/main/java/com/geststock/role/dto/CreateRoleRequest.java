package com.geststock.role.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Création d'un rôle personnalisé.
 *
 * @param code        identifiant stable, en majuscules (non modifiable ensuite)
 * @param permissions codes {@code domaine:action} ; liste vide autorisée (rôle sans droit)
 */
@Schema(name = "CreateRoleRequest")
public record CreateRoleRequest(
		@NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,49}$",
				message = "2 à 50 caractères : majuscules, chiffres et souligné, commençant par une lettre")
		@Schema(example = "VENDEUR_SENIOR") String code,
		@NotBlank @Size(max = 100) @Schema(example = "Vendeur senior") String label,
		@Size(max = 255) String description,
		@NotNull List<@NotBlank String> permissions) {
}
