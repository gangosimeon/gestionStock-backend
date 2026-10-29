package com.geststock.warehouse.event;

import java.util.UUID;

/**
 * Publié dans la transaction qui active ou désactive réellement un dépôt (changement d'état seulement) : le
 * périmètre de la disponibilité en ligne change pour tous les produits stockés dans ce dépôt.
 */
public record WarehouseActivationChangedEvent(UUID warehouseId, boolean active) {
}
