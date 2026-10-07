package com.geststock.user.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Représentation d'un utilisateur. Ne contient <b>jamais</b> le mot de passe ni son haché :
 * seul {@code hasPassword} indique si le compte peut se connecter.
 *
 * <p>Compatibilité front : {@code fullName}, {@code roles} et {@code isActive} reprennent le
 * contrat existant ; {@code role} (ADMIN / MANAGER / EMPLOYEE) alimente l'en-tête de
 * l'application tant que le front n'est pas unifié sur les rôles métier.
 */
@Schema(name = "User")
public record UserResponse(
		UUID id,
		@Schema(example = "pierre.d") String username,
		@Schema(example = "Pierre") String firstName,
		@Schema(example = "Diallo") String lastName,
		@Schema(example = "Pierre Diallo") String fullName,
		@Schema(example = "pierre.d@optivision.ci") String email,
		@Schema(example = "+225 07 00 00 03") String phone,
		@Schema(description = "Rôles métier", example = "[\"CAISSIER\"]") List<String> roles,
		@Schema(description = "Rôle d'affichage historique du front", example = "EMPLOYEE") String role,
		@JsonProperty("isActive") @Schema(name = "isActive") boolean isActive,
		@Schema(description = "Un mot de passe est défini (le compte peut se connecter)") boolean hasPassword,
		@Schema(description = "Le mot de passe actuel est temporaire") boolean mustChangePassword,
		UUID defaultWarehouseId,
		List<UUID> warehouseIds,
		@Schema(description = "Dernière connexion réussie") Instant lastLoginAt,
		Instant createdAt,
		Instant updatedAt) {
}
