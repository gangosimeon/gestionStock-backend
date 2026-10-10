package com.geststock.inventory.dto;

import java.time.Instant;

import com.geststock.inventory.InventoryStatus;

/**
 * Critères de recherche des inventaires.
 *
 * @param from          inventaires créés à partir de cet instant (inclus)
 * @param to            inventaires créés avant cet instant (exclu)
 * @param allWarehouses tous les dépôts (exige {@code warehouse:all}) au lieu du dépôt courant
 */
public record InventoryFilter(InventoryStatus status, Instant from, Instant to, Boolean allWarehouses) {
}
