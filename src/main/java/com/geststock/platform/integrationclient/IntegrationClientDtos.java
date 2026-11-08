package com.geststock.platform.integrationclient;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/** Contrats de l'API Super Admin des clients d'intégration OAuth ({@code /api/platform/integration-clients}). */
class IntegrationClientDtos {

	private IntegrationClientDtos() {
	}

	@Schema(name = "CreateIntegrationClientRequest")
	record CreateClientRequest(
			@NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{2,62}[a-z0-9]$",
					message = "4 à 64 caractères : minuscules, chiffres, tirets ; commence et finit par un alphanumérique")
			@Schema(example = "jecreemaboutique-prod") String clientId,
			@NotBlank @Size(max = 100) @Schema(example = "JeCréeMaBoutique") String name,
			@NotEmpty @Schema(description = "URLs de retour OAuth exactes (https ou http://localhost)")
			List<@Pattern(regexp = "^(https://[^#]+|http://localhost[^#]*)$",
					message = "https:// ou http://localhost, sans fragment") String> redirectUris,
			@Schema(description = "Portées autorisées ; toutes par défaut")
			Set<@Pattern(regexp = "^(catalog:read|catalog:match|price:propose|stock:read|orders:read|orders:write)$",
					message = "Portée inconnue") String> allowedScopes) {
	}

	@Schema(name = "IntegrationClientListItem")
	record ClientListItem(UUID id, String clientId, String name, String status, Instant createdAt) {
	}

	@Schema(name = "IntegrationClientResponse")
	record ClientResponse(UUID id, String clientId, String name, String status,
			Instant createdAt, Instant updatedAt) {
	}

	@Schema(name = "CreateIntegrationClientResponse",
			description = "Le secret est affiché une seule fois : notez-le immédiatement.")
	record CreateClientResponse(ClientResponse client,
			@Schema(description = "Secret client en clair — affiché une seule fois") String secret) {
	}

	@Schema(name = "ResetSecretResponse",
			description = "Le nouveau secret est affiché une seule fois : notez-le immédiatement.")
	record ResetSecretResponse(
			@Schema(description = "Nouveau secret client en clair — affiché une seule fois") String secret) {
	}

}
