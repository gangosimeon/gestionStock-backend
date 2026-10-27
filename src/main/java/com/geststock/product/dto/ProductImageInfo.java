package com.geststock.product.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Photo enregistrée d'un produit (après réduction par le serveur).
 *
 * @param version empreinte de l'image : change à chaque nouvelle photo (cache du navigateur)
 */
@Schema(name = "ProductImage")
public record ProductImageInfo(UUID productId, @Schema(example = "image/jpeg") String contentType, int sizeBytes,
		int width, int height, String version) {
}
