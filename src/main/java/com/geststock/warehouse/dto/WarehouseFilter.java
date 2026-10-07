package com.geststock.warehouse.dto;

import com.geststock.common.api.ActiveStatus;
import com.geststock.warehouse.WarehouseType;

/**
 * Critères de recherche des dépôts.
 *
 * @param q      texte cherché dans le code et le nom
 * @param status ACTIVE (défaut), INACTIVE ou ALL
 * @param type   STORE ou WAREHOUSE
 */
public record WarehouseFilter(String q, ActiveStatus status, WarehouseType type) {
}
