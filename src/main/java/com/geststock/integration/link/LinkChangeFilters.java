package com.geststock.integration.link;

import com.geststock.integration.ChangeFilter;
import com.geststock.integration.IntegrationEventType;

/**
 * Filtre du flux selon les portées effectives de la liaison (revue G16) : la disponibilité ({@code AVAILABILITY},
 * quantités de stock par dépôt) n'est servie qu'avec {@code stock:read}, la portée de {@code GET /availability} ;
 * {@code catalog:read} seule ne divulgue aucune quantité. Les autres types, connus ou non, sont servis (Q2 : catalogue
 * actif entier). Un événement écarté est quand même examiné et fait avancer {@code lastSeq} (P1).
 */
final class LinkChangeFilters {

	static final String STOCK_READ = "stock:read";

	/** Tous les types sauf la disponibilité. */
	private static final ChangeFilter WITHOUT_AVAILABILITY = (type, objectId) -> type != IntegrationEventType.AVAILABILITY;

	private LinkChangeFilters() {
	}

	/** Filtre de la liaison appelante, selon ses portées effectives (jeton ∩ liaison). */
	static ChangeFilter of(LinkPrincipal principal) {
		return principal.hasScope(STOCK_READ) ? ChangeFilter.ALL : WITHOUT_AVAILABILITY;
	}

}
