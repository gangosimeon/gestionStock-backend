package com.geststock.supplier.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.common.api.ActiveStatus;

/**
 * Critères de recherche des fournisseurs.
 *
 * @param q      texte cherché dans le code, le nom, l'interlocuteur, l'email et le téléphone
 * @param status ACTIVE (défaut), INACTIVE ou ALL
 */
public record SupplierFilter(
		@Schema(example = "essilor") String q,
		@Schema(description = "ACTIVE (défaut), INACTIVE ou ALL") ActiveStatus status) {
}
