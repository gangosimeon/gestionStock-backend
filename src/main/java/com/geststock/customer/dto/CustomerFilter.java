package com.geststock.customer.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.common.api.ActiveStatus;
import com.geststock.customer.CustomerType;
import com.geststock.customer.PriceCategory;

/**
 * Critères de recherche des clients.
 *
 * @param q             texte cherché dans le code, le nom, l'email et le téléphone (chiffres
 *                      seuls acceptés : « 0510 » trouve « +225 05 10 20 30 »)
 * @param status        ACTIVE (défaut), INACTIVE ou ALL
 * @param type          INDIVIDUAL ou COMPANY
 * @param priceCategory RETAIL ou WHOLESALE
 */
public record CustomerFilter(
		@Schema(example = "0510") String q,
		@Schema(description = "ACTIVE (défaut), INACTIVE ou ALL") ActiveStatus status,
		CustomerType type,
		PriceCategory priceCategory) {
}
