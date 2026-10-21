package com.geststock.security.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Entreprise de l'utilisateur connecté (affichage, et code à renvoyer au renouvellement de session).
 *
 * @param code code public de l'entreprise
 * @param name nom commercial
 */
@Schema(name = "CompanySummary")
public record CompanySummary(@Schema(example = "quincaillerie-akwaba") String code,
		@Schema(example = "Quincaillerie Akwaba") String name) {
}
