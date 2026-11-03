package com.geststock.integration;

import java.time.Instant;
import java.util.UUID;

/**
 * Prix d'un produit pour une liaison (contrat {@code ChannelPrice} ; lot 3, G22a ; D2, v16-D3) : prix de référence de
 * GestStock, dernier prix approuvé (seul commercialisable) et dernière proposition de la boutique.
 *
 * @param productId                     produit
 * @param referencePrice                prix de référence courant ({@code product.retail_price}), deux décimales
 * @param referenceVersion              version courante du prix de référence ({@code product.version})
 * @param approved                      dernier prix approuvé ; {@code null} si aucun prix n'a été approuvé
 * @param pending                       dernière proposition de la liaison, si elle n'est pas le prix approuvé ;
 *                                      {@code null} sinon
 * @param referenceChangedSinceApproval le prix de référence a changé depuis l'approbation (alerte seulement, v16-D3)
 */
public record ChannelPrice(UUID productId, String referencePrice, long referenceVersion, Approved approved,
		Pending pending, boolean referenceChangedSinceApproval) {

	/**
	 * @param price        prix approuvé, deux décimales
	 * @param currency     devise de la liaison
	 * @param priceVersion rang de la proposition approuvée pour (liaison, produit)
	 * @param approvedAt   date de l'approbation
	 */
	public record Approved(String price, String currency, long priceVersion, Instant approvedAt) {
	}

	/**
	 * @param proposalId      proposition
	 * @param price           prix proposé, deux décimales
	 * @param status          {@code PROPOSED}, {@code REJECTED}, {@code SUPERSEDED} ou {@code WITHDRAWN}
	 * @param rejectionReason motif d'un refus ; {@code null} sinon
	 */
	public record Pending(UUID proposalId, String price, String status, String rejectionReason) {
	}

}
