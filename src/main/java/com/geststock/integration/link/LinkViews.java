package com.geststock.integration.link;

import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

import com.geststock.integration.LinkWarehouses;
import com.geststock.integration.LinkWarehouses.WarehouseChoice;
import com.geststock.platform.PlatformDb;
import com.geststock.tenancy.TenantContext;
import com.geststock.tenancy.TenantRef;
import com.geststock.tenancy.TenantSchemaRegistry;

/**
 * Construction de la vue {@link LinkView} (lot 2, étape G11) : liaison (base plateforme) et ses dépôts (base de
 * l'entreprise).
 */
@Component
class LinkViews {

	private static final Logger log = LoggerFactory.getLogger(LinkViews.class);

	private final PlatformDb platform;

	private final LinkWarehouses warehouses;

	private final TenantSchemaRegistry schemas;

	LinkViews(PlatformDb platform, LinkWarehouses warehouses, TenantSchemaRegistry schemas) {
		this.platform = platform;
		this.warehouses = warehouses;
		this.schemas = schemas;
	}

	/** Vue avec les dépôts de la liaison, lus dans la base de l'entreprise (qui doit être lisible). */
	LinkView withWarehouses(Link link, Set<String> scopes, TenantRef company) {
		return LinkView.of(link, scopes, company.code(), TenantContext.callAs(company, () -> linkWarehouses(link)));
	}

	/** Vue sans dépôts : liaison révoquée ({@code POST /link/revoke}, base plateforme seulement) ; aucun dépôt vendable. */
	LinkView withoutWarehouses(Link link, Set<String> scopes, TenantRef company) {
		return LinkView.of(link, scopes, company.code(), List.of());
	}

	/**
	 * Échange du code ({@code /oauth/token}) : la liaison vient d'être activée et son secret émis, qui doit être remis.
	 * Base de l'entreprise illisible (entreprise non active, migration en cours ou en échec, base indisponible) :
	 * dépôts vides plutôt qu'un échec de l'échange ; {@code GET /link} les donnera.
	 */
	LinkView afterExchange(Link link) {
		Company company = this.platform.jdbc().sql("select code, status from platform.company where id = :id")
			.param("id", link.companyId())
			.query((rs, n) -> new Company(new TenantRef(link.companyId(), rs.getString("code")), rs.getString("status")))
			.single();
		boolean readable = "ACTIVE".equals(company.status()) && this.schemas.blockingState(link.companyId()).isEmpty();
		if (readable) {
			try {
				return withWarehouses(link, link.scopes(), company.ref());
			}
			catch (RuntimeException ex) {
				log.warn("Dépôts de la liaison {} non lus à l'échange du code ({}) : liste vide, lisible par GET /link.",
						link.id(), ex.getClass().getSimpleName());
			}
		}
		else {
			log.info("Dépôts de la liaison {} non lus à l'échange du code : base de l'entreprise non disponible.", link.id());
		}
		return LinkView.of(link, link.scopes(), company.ref().code(), List.of());
	}

	private List<WarehouseChoice> linkWarehouses(Link link) {
		return this.warehouses.ofLinks(List.of(link.id())).getOrDefault(link.id(), List.of());
	}

	private record Company(TenantRef ref, String status) {
	}

}
