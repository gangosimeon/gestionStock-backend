package com.geststock.integration;

import java.util.UUID;

/**
 * Schéma {@code ProductImageState} du contrat : charge des événements {@code PRODUCT_IMAGE} et entrée de la
 * section {@code productImages} de l'instantané (même format, une seule définition).
 *
 * @param productId   produit
 * @param imageSha256 empreinte de l'image courante ; {@code null} si le produit n'a pas (ou plus) d'image
 * @param version     génération de l'image (P7, lue et écrite par {@link OutboxWriter}) ; 0 dans l'instantané si
 *                    le produit n'a jamais eu d'image
 */
record ProductImageState(UUID productId, String imageSha256, long version) {
}
