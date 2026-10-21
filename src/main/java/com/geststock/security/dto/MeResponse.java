package com.geststock.security.dto;

import java.util.Set;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.user.dto.UserResponse;

/**
 * Utilisateur connecté : profil (mêmes champs que {@code /api/users}, à plat) et
 * permissions effectives calculées par le serveur. Le front s'en sert pour adapter
 * l'affichage ; il ne décide jamais des droits, chaque endpoint les vérifie.
 */
@Schema(name = "Me")
public record MeResponse(
		@JsonUnwrapped UserResponse profile,
		@Schema(description = "Permissions effectives (domaine:action)", example = "[\"sale:create\", \"cash:operate\"]")
		Set<String> permissions,
		@Schema(description = "Entreprise de l'utilisateur") CompanySummary company) {
}
