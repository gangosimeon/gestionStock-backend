package com.geststock.integration.link;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.integration.LinkWarehouses.WarehouseChoice;

/**
 * Liaison au format {@code Link} du contrat d'intégration (lot 2, étape G11) : seule vue d'une liaison servie à la
 * boutique, par {@code GET /link}, {@code POST /link/revoke} et {@code /oauth/token} (grant {@code authorization_code}).
 * Jamais d'empreinte de secret. Les réglages ({@code settings}) et la dernière synchronisation ({@code lastSyncAt})
 * arrivent avec les lots qui les définissent : objet vide et {@code null} d'ici là.
 *
 * @param scopes     portées effectives (G7 : portées du jeton ∩ portées de la liaison en base)
 * @param warehouses dépôts vendables de la liaison (G4a) ; vide si la liaison est révoquée ou si la base de
 *                   l'entreprise n'a pas pu être lue (voir {@link LinkViews})
 */
@Schema(name = "IntegrationLinkView")
public record LinkView(UUID id, LinkStatus status, SuspensionReason suspensionReason, RevocationReason revocationReason,
		String companyCode, String currency, String externalShopId, List<String> scopes, List<Warehouse> warehouses,
		Map<String, Object> settings, Instant createdAt, Instant lastSyncAt, Instant revokedAt) {

	static LinkView of(Link link, Set<String> scopes, String companyCode, List<WarehouseChoice> warehouses) {
		return new LinkView(link.id(), link.status(), link.suspensionReason(), link.revocationReason(), companyCode,
				link.currency(), link.externalShopId(), List.copyOf(new TreeSet<>(scopes)),
				warehouses.stream().map(w -> new Warehouse(w.id(), w.name(), w.active())).toList(), Map.of(), link.createdAt(),
				null, link.revokedAt());
	}

	/** Élément de {@code Link.warehouses} ; {@code priority} (facultative) n'existe pas encore : omise. */
	@Schema(name = "IntegrationLinkViewWarehouse")
	public record Warehouse(UUID warehouseId, String name, boolean active) {
	}

}
