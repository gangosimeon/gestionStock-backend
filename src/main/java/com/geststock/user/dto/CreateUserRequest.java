package com.geststock.user.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Création d'un utilisateur.
 *
 * <p>Nom : fournir {@code lastName} (et éventuellement {@code firstName}), ou
 * {@code fullName} (contrat actuel du front, découpé au premier espace).
 * Mot de passe facultatif : sans mot de passe, le compte existe mais ne peut pas se
 * connecter tant qu'un administrateur ne lui en attribue pas un.
 */
@Schema(name = "CreateUserRequest")
public record CreateUserRequest(
		@NotBlank @Pattern(regexp = UserConstraints.USERNAME_PATTERN, message = UserConstraints.USERNAME_MESSAGE)
		@Schema(example = "pierre.d") String username,
		@Size(max = UserConstraints.NAME_MAX) @Schema(example = "Pierre") String firstName,
		@Size(max = UserConstraints.NAME_MAX) @Schema(example = "Diallo") String lastName,
		@Size(max = UserConstraints.FULL_NAME_MAX)
		@Schema(description = "Alternative à firstName/lastName (front actuel)", example = "Pierre Diallo") String fullName,
		@Email @Size(max = UserConstraints.EMAIL_MAX) @Schema(example = "pierre.d@optivision.ci") String email,
		@Size(max = UserConstraints.PHONE_MAX) @Schema(example = "+225 07 00 00 03") String phone,
		@Schema(description = "Mot de passe initial (temporaire) ; jamais renvoyé", accessMode = Schema.AccessMode.WRITE_ONLY)
		String password,
		@NotEmpty(message = "au moins un rôle est requis") @Schema(example = "[\"CAISSIER\"]") List<@NotBlank String> roles,
		@JsonProperty("isActive") @Schema(name = "isActive", description = "Actif par défaut") Boolean isActive,
		UUID defaultWarehouseId,
		List<UUID> warehouseIds) {

	@AssertTrue(message = "le nom (lastName) ou le nom complet (fullName) est requis")
	@Schema(hidden = true)
	public boolean isNameProvided() {
		return UserConstraints.hasText(this.lastName) || UserConstraints.hasText(this.fullName);
	}

	/** Masque le mot de passe si la requête est journalisée par erreur. */
	@Override
	public String toString() {
		return "CreateUserRequest[username=" + this.username + ", lastName=" + this.lastName + ", roles=" + this.roles
				+ ", password=" + (this.password != null ? "***" : "null") + "]";
	}

}
