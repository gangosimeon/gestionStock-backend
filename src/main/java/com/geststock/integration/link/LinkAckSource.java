package com.geststock.integration.link;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.geststock.integration.AckFloor;
import com.geststock.integration.AckSource;
import com.geststock.tenancy.TenantContext;

/**
 * Limite des acquittements pour l'entreprise courante (plan § 30.4, étapes 2 et 3 ; v17-D4, v12-D4) : liaisons
 * actives ou suspendues lues dans la base plateforme ({@link RetainedLinks}), puis leurs acquittements dans la base de
 * l'entreprise ({@link LinkAcknowledgements}). L'entreprise est celle du contexte (jamais une valeur reçue) ; sans
 * contexte, refus.
 */
@Component
class LinkAckSource implements AckSource {

	private final RetainedLinks links;

	private final LinkAcknowledgements acknowledgements;

	LinkAckSource(RetainedLinks links, LinkAcknowledgements acknowledgements) {
		this.links = links;
		this.acknowledgements = acknowledgements;
	}

	@Override
	public AckFloor floor() {
		Set<UUID> retained = this.links.of(TenantContext.require().companyId());
		if (retained.isEmpty()) {
			return AckFloor.noLink();
		}
		Map<UUID, Long> acknowledged = this.acknowledgements.of(retained);
		if (!acknowledged.keySet().containsAll(retained)) {
			return AckFloor.blocked();
		}
		return AckFloor.acknowledged(Collections.min(acknowledged.values()));
	}

}
