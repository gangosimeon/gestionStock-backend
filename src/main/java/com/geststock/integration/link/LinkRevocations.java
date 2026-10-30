package com.geststock.integration.link;

import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * Révocation d'une liaison à l'initiative de la boutique ({@code POST /link/revoke}, C2 ; lot 2, étape G11) : motif
 * {@code SHOP_REQUEST}, base plateforme seulement.
 */
@Component
class LinkRevocations {

	private final LinkRegistry links;

	LinkRevocations(LinkRegistry links) {
		this.links = links;
	}

	/**
	 * @param reason motif libre facultatif de la boutique (500 caractères au plus) : destiné au journal d'audit
	 *               (étape G12), qui l'inscrira ici ; il n'est ni stocké ailleurs ni journalisé
	 * @throws LinkController.LinkAlreadyRevoked liaison déjà révoquée (révocation simultanée)
	 */
	Link byShop(UUID linkId, String reason) {
		try {
			return this.links.revoke(linkId, RevocationReason.SHOP_REQUEST);
		}
		catch (LinkException ex) {
			if (ex.reason() == LinkException.Reason.INVALID_TRANSITION || ex.reason() == LinkException.Reason.LINK_NOT_FOUND) {
				throw new LinkController.LinkAlreadyRevoked();
			}
			throw ex;
		}
	}

}
