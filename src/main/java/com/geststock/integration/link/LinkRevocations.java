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

	private final LinkAudit audit;

	LinkRevocations(LinkRegistry links, LinkAudit audit) {
		this.links = links;
		this.audit = audit;
	}

	/**
	 * @param reason motif libre facultatif de la boutique (500 caractères au plus) : inscrit tel quel dans la trace
	 *               d'audit (G12), dans la transaction de la révocation ; il n'est ni stocké ailleurs ni journalisé
	 * @throws LinkController.LinkAlreadyRevoked liaison déjà révoquée (révocation simultanée)
	 */
	Link byShop(UUID linkId, String reason) {
		try {
			return this.audit.unit(() -> {
				Link revoked = this.links.revoke(linkId, RevocationReason.SHOP_REQUEST);
				this.audit.record(LinkAudit.Entry.of(LinkAudit.REVOKED, revoked, LinkAudit.Actor.shop(revoked)).reason(reason)
					.detail("revocationReason", RevocationReason.SHOP_REQUEST.name()));
				return revoked;
			});
		}
		catch (LinkException ex) {
			if (ex.reason() == LinkException.Reason.INVALID_TRANSITION || ex.reason() == LinkException.Reason.LINK_NOT_FOUND) {
				throw new LinkController.LinkAlreadyRevoked();
			}
			throw ex;
		}
	}

}
