package com.geststock.permission.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Permission du catalogue.
 *
 * @param code        identifiant vérifié par le serveur ({@code domaine:action})
 * @param domain      domaine fonctionnel (regroupement à l'écran)
 * @param action      action autorisée
 * @param description libellé en français
 */
@Schema(name = "Permission")
public record PermissionResponse(
		@Schema(example = "product:create") String code,
		@Schema(example = "product") String domain,
		@Schema(example = "create") String action,
		@Schema(example = "Créer des produits") String description) {
}
