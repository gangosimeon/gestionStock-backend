package com.geststock.integration.link;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Liaison entre une entreprise GestStock et une boutique JeCréeMaBoutique ({@code platform.integration_link}). Ne porte
 * jamais d'empreinte ni de secret.
 *
 * @param id                   identifiant (claim {@code sub} des jetons de liaison)
 * @param clientId             client JeCréeMaBoutique ({@code integration_client.id})
 * @param companyId            entreprise liée
 * @param externalShopId       boutique ({@code User._id} JeCréeMaBoutique)
 * @param shopName             nom de la boutique à l'autorisation
 * @param status               statut
 * @param suspensionReason     motif de suspension, si {@code SUSPENDED}
 * @param revocationReason     motif de révocation, si {@code REVOKED}
 * @param currency             devise ISO 4217 de la liaison (v11-D6)
 * @param scopes               portées accordées
 * @param authorizedBy         utilisateur de l'entreprise qui a approuvé
 * @param authorizedByUsername son nom d'utilisateur
 * @param createdAt            création (approbation)
 * @param activatedAt          activation (échange du code)
 * @param suspendedAt          dernière suspension
 * @param revokedAt            révocation
 */
public record Link(UUID id, UUID clientId, UUID companyId, String externalShopId, String shopName, LinkStatus status,
		SuspensionReason suspensionReason, RevocationReason revocationReason, String currency, Set<String> scopes,
		UUID authorizedBy, String authorizedByUsername, Instant createdAt, Instant activatedAt, Instant suspendedAt,
		Instant revokedAt) {

	public Link {
		scopes = Set.copyOf(scopes);
	}

}
