package com.geststock.integration;

import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * Page de l'instantané (schéma {@code SnapshotPage} du contrat v11, décision v11-D10).
 *
 * @param asOfSeq       fixé à la première page, identique sur toutes les pages ; reprendre le flux après lui
 * @param currency      devise de l'entreprise, ISO 4217
 * @param categories    schéma {@code Category}
 * @param products      schéma {@code Product}
 * @param productImages schéma {@code ProductImageState}
 * @param availability  schéma {@code Availability} ; version de chaque entrée = {@code asOfSeq}
 * @param nextPageToken jeton de la page suivante ; {@code null} sur la dernière page (champ absent du contrat)
 */
public record SnapshotPage(long asOfSeq, String currency, List<JsonNode> categories, List<JsonNode> products,
		List<JsonNode> productImages, List<JsonNode> availability, String nextPageToken) {

	public SnapshotPage {
		categories = List.copyOf(categories);
		products = List.copyOf(products);
		productImages = List.copyOf(productImages);
		availability = List.copyOf(availability);
	}

	/** Nombre d'objets de la page, toutes sections confondues. */
	public int size() {
		return this.categories.size() + this.products.size() + this.productImages.size() + this.availability.size();
	}

}
