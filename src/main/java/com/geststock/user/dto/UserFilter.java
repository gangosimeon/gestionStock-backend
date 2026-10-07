package com.geststock.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Critères de recherche des utilisateurs (paramètres de requête).
 *
 * @param q      texte recherché dans l'identifiant, le nom, le prénom et l'email
 * @param active {@code true} = actifs, {@code false} = désactivés, absent = tous
 * @param role   code de rôle (ADMIN, GESTIONNAIRE, CAISSIER)
 */
public record UserFilter(
		@Schema(description = "Recherche texte (identifiant, nom, prénom, email)", example = "diallo") String q,
		@Schema(description = "Filtre actif / désactivé ; absent = tous") Boolean active,
		@Schema(description = "Code de rôle", example = "CAISSIER") String role) {
}
