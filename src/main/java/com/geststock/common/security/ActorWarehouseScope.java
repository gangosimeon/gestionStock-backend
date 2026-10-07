package com.geststock.common.security;

import java.util.Set;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Périmètre de dépôts de l'utilisateur à l'origine de la requête.
 *
 * @param allWarehouses      permission {@code warehouse:all} : accès à tous les dépôts
 * @param defaultWarehouseId dépôt par défaut, ou {@code null}
 * @param warehouseIds       dépôts attribués
 */
public record ActorWarehouseScope(boolean allWarehouses, UUID defaultWarehouseId, Set<UUID> warehouseIds) {

	public static ActorWarehouseScope current() {
		boolean all = ActorPermissions.current().contains(Permissions.WAREHOUSE_ALL);
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof WarehouseScopedPrincipal principal) {
			return new ActorWarehouseScope(all, principal.defaultWarehouseId(), Set.copyOf(principal.warehouseIds()));
		}
		return new ActorWarehouseScope(all, null, Set.of());
	}

	/** Aucun dépôt attribué ni par défaut : l'utilisateur travaille dans le dépôt principal. */
	public boolean unassigned() {
		return this.defaultWarehouseId == null && this.warehouseIds.isEmpty();
	}

}
