package com.geststock.integration;

import java.util.UUID;

/**
 * Objet d'une section de l'instantané.
 *
 * @param id   clé de pagination (identifiant de l'objet, ou du produit pour les images et la disponibilité)
 * @param data charge, sérialisée par le {@code JsonMapper} comme celle des événements
 */
public record SnapshotItem(UUID id, Object data) {
}
