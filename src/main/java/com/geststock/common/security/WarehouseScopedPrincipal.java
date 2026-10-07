package com.geststock.common.security;

import java.util.Set;
import java.util.UUID;

/**
 * Utilisateur authentifié porteur de son périmètre de dépôts (relu en base à chaque
 * requête). Implémenté par l'utilisateur courant du module sécurité.
 */
public interface WarehouseScopedPrincipal {

	/** Dépôt par défaut de l'utilisateur, ou {@code null}. */
	UUID defaultWarehouseId();

	/** Dépôts explicitement attribués (peut être vide). */
	Set<UUID> warehouseIds();

}
