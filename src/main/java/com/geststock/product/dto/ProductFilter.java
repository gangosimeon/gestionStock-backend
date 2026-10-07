package com.geststock.product.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.common.api.ActiveStatus;

/**
 * Critères de recherche des produits.
 *
 * @param q          texte recherché dans la référence, le nom et le code-barres
 * @param categoryId catégorie
 * @param brandId    marque
 * @param supplierId fournisseur habituel
 * @param status     ACTIVE (défaut), INACTIVE ou ALL
 */
public record ProductFilter(
		@Schema(example = "ray-ban") String q,
		UUID categoryId,
		UUID brandId,
		UUID supplierId,
		@Schema(description = "ACTIVE (défaut), INACTIVE ou ALL") ActiveStatus status) {
}
