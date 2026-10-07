package com.geststock.user.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Modification d'un utilisateur ({@code PUT}). Identifiant, nom et rôles sont remplacés.
 *
 * <p>Champs facultatifs ({@code email}, {@code phone}, {@code defaultWarehouseId},
 * {@code warehouseIds}) : absents ou {@code null} = inchangés (le front actuel ne les envoie
 * pas) ; chaîne vide pour effacer un email ou un téléphone. Le mot de passe et le statut se
 * modifient par leurs routes dédiées.
 */
@Schema(name = "UpdateUserRequest")
public record UpdateUserRequest(
		@NotBlank @Pattern(regexp = UserConstraints.USERNAME_PATTERN, message = UserConstraints.USERNAME_MESSAGE)
		String username,
		@Size(max = UserConstraints.NAME_MAX) String firstName,
		@Size(max = UserConstraints.NAME_MAX) String lastName,
		@Size(max = UserConstraints.FULL_NAME_MAX) String fullName,
		@Email @Size(max = UserConstraints.EMAIL_MAX) String email,
		@Size(max = UserConstraints.PHONE_MAX) String phone,
		@NotEmpty(message = "au moins un rôle est requis") List<@NotBlank String> roles,
		UUID defaultWarehouseId,
		List<UUID> warehouseIds) {

	@AssertTrue(message = "le nom (lastName) ou le nom complet (fullName) est requis")
	@Schema(hidden = true)
	public boolean isNameProvided() {
		return UserConstraints.hasText(this.lastName) || UserConstraints.hasText(this.fullName);
	}

}
