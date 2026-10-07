package com.geststock.product;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Vue minimale d'un produit pour les autres modules (stock, ventes, achats) : identité,
 * libellés et prix d'achat courant. Lecture seule.
 */
public record ProductSnapshot(UUID id, String sku, String name, boolean active, BigDecimal purchasePrice) {

	static ProductSnapshot of(Product product) {
		return new ProductSnapshot(product.getId(), product.getSku(), product.getName(), product.isActive(),
				product.getPurchasePrice());
	}

}
