package com.geststock.integration.link;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

import com.geststock.integration.OnlineOrders;
import com.geststock.platform.PlatformDb;
import com.geststock.tenancy.TenantContext;
import com.geststock.tenancy.TenantRef;

/**
 * Révocation d'une liaison à l'initiative de la boutique ({@code POST /link/revoke}, C2 ; lot 2, étape G11) : motif
 * {@code SHOP_REQUEST}, base plateforme seulement. Après la révocation de la liaison, les réservations actives de
 * l'entreprise sont libérées ({@link OnlineOrders#releaseReservationsForLink}).
 */
@Component
class LinkRevocations {

	private static final Logger log = LoggerFactory.getLogger(LinkRevocations.class);

	private final LinkRegistry links;

	private final LinkAudit audit;

	private final OnlineOrders orders;

	private final PlatformDb platform;

	LinkRevocations(LinkRegistry links, LinkAudit audit, OnlineOrders orders, PlatformDb platform) {
		this.links = links;
		this.audit = audit;
		this.orders = orders;
		this.platform = platform;
	}

	/**
	 * @param reason motif libre facultatif de la boutique (500 caractères au plus) : inscrit tel quel dans la trace
	 *               d'audit (G12), dans la transaction de la révocation ; il n'est ni stocké ailleurs ni journalisé
	 * @throws LinkController.LinkAlreadyRevoked liaison déjà révoquée (révocation simultanée)
	 */
	Link byShop(UUID linkId, String reason) {
		Link revoked;
		try {
			revoked = this.audit.unit(() -> {
				Link r = this.links.revoke(linkId, RevocationReason.SHOP_REQUEST);
				this.audit.record(LinkAudit.Entry.of(LinkAudit.REVOKED, r, LinkAudit.Actor.shop(r)).reason(reason)
					.detail("revocationReason", RevocationReason.SHOP_REQUEST.name()));
				return r;
			});
		}
		catch (LinkException ex) {
			if (ex.reason() == LinkException.Reason.INVALID_TRANSITION || ex.reason() == LinkException.Reason.LINK_NOT_FOUND) {
				throw new LinkController.LinkAlreadyRevoked();
			}
			throw ex;
		}
		// Après la transaction plateforme : libérer les réservations actives dans la base de l'entreprise.
		releaseReservations(revoked);
		return revoked;
	}

	/**
	 * Libère les réservations actives de la liaison dans la base de l'entreprise. Appelé après la révocation
	 * (plateforme), dans un contexte d'entreprise temporaire. Un échec ne remonte jamais à l'appelant :
	 * la révocation a déjà eu lieu, les réservations expireront naturellement.
	 */
	private void releaseReservations(Link revoked) {
		try {
			String code = this.platform.jdbc()
				.sql("select code from platform.company where id = :id")
				.param("id", revoked.companyId())
				.query(String.class)
				.single();
			TenantRef company = new TenantRef(revoked.companyId(), code);
			TenantContext.runAs(company, () -> {
				int released = this.orders.releaseReservationsForLink(revoked.id());
				if (released > 0) {
					log.info("Révocation de la liaison {} : {} réservation(s) libérée(s).", revoked.id(), released);
				}
			});
		}
		catch (Exception ex) {
			log.warn("Impossible de libérer les réservations de la liaison {} après révocation : {}.",
					revoked.id(), ex.getMessage());
		}
	}

}
