package com.geststock.security;

import java.util.Set;
import java.util.UUID;

import com.geststock.common.security.WarehouseScopedPrincipal;

/**
 * Utilisateur authentifié de la requête en cours. Rôles et permissions sont relus en base
 * à chaque requête (jamais pris dans le jeton ni fournis par le client) : un changement de
 * rôle ou une désactivation prend effet immédiatement.
 *
 * <p>Injection dans un contrôleur : {@code @AuthenticationPrincipal CurrentUser user}.
 */
public record CurrentUser(UUID id, String username, Set<String> roles, Set<String> permissions, UUID defaultWarehouseId,
		Set<UUID> warehouseIds) implements WarehouseScopedPrincipal {

	public boolean hasPermission(String permission) {
		return this.permissions.contains(permission);
	}

}
