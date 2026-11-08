package com.geststock.platform.integrationclient;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;

import com.geststock.exception.ApiError;
import com.geststock.platform.admin.PlatformAuthentication;
import com.geststock.platform.integrationclient.IntegrationClientDtos.ClientListItem;
import com.geststock.platform.integrationclient.IntegrationClientDtos.ClientResponse;
import com.geststock.platform.integrationclient.IntegrationClientDtos.CreateClientRequest;
import com.geststock.platform.integrationclient.IntegrationClientDtos.CreateClientResponse;
import com.geststock.platform.integrationclient.IntegrationClientDtos.ResetSecretResponse;
import com.geststock.platform.integrationclient.IntegrationClientDtos.UpdateClientRequest;

/**
 * Administration des clients OAuth d'intégration (Super Admin, lot 2 ; G8) :
 * création, consultation, réinitialisation du secret, activation/désactivation.
 */
@RestController
@RequestMapping("/api/platform/integration-clients")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Tag(name = "Plateforme — clients d'intégration",
		description = "Gestion des clients OAuth pour la liaison boutiques (Super Admin)")
@ApiResponse(responseCode = "403", description = "Réservé au Super Admin",
		content = @Content(schema = @Schema(implementation = ApiError.class)))
class IntegrationClientController {

	private final IntegrationClientService service;

	IntegrationClientController(IntegrationClientService service) {
		this.service = service;
	}

	@GetMapping
	@Operation(summary = "Liste des clients d'intégration")
	@ApiResponse(responseCode = "200", description = "OK")
	List<ClientListItem> list() {
		return this.service.list();
	}

	@GetMapping("/{id}")
	@Operation(summary = "Détail d'un client d'intégration")
	@ApiResponse(responseCode = "200", description = "OK")
	@ApiResponse(responseCode = "404", description = "INTEGRATION_CLIENT_NOT_FOUND",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	ClientResponse get(@PathVariable UUID id) {
		return this.service.get(id);
	}

	@PostMapping
	@Operation(summary = "Créer un client d'intégration",
			description = "Le secret est affiché une seule fois dans la réponse : notez-le immédiatement.")
	@ApiResponse(responseCode = "201", description = "Client créé, secret en clair dans la réponse")
	@ApiResponse(responseCode = "409", description = "INTEGRATION_CLIENT_ID_ALREADY_USED",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	ResponseEntity<CreateClientResponse> create(
			@Valid @RequestBody CreateClientRequest request,
			@AuthenticationPrincipal PlatformAuthentication.Principal admin) {
		IntegrationClientService.CreateResult result = this.service.create(request, admin.id());
		return ResponseEntity
				.created(URI.create("/api/platform/integration-clients/" + result.client().id()))
				.body(new CreateClientResponse(result.client(), result.secret()));
	}

	@PatchMapping("/{id}")
	@Operation(summary = "Modifier le nom et les URLs de retour d'un client")
	@ApiResponse(responseCode = "200", description = "Client mis à jour")
	@ApiResponse(responseCode = "404", description = "INTEGRATION_CLIENT_NOT_FOUND",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	ClientResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateClientRequest request) {
		return this.service.update(id, request);
	}

	@PostMapping("/{id}/reset-secret")
	@Operation(summary = "Réinitialiser le secret d'un client",
			description = "Génère un nouveau secret et efface l'ancien. L'ancien secret cesse immédiatement de fonctionner. "
					+ "Le nouveau secret est affiché une seule fois : notez-le immédiatement.")
	@ApiResponse(responseCode = "200", description = "Nouveau secret en clair")
	@ApiResponse(responseCode = "404", description = "INTEGRATION_CLIENT_NOT_FOUND",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	ResetSecretResponse resetSecret(@PathVariable UUID id) {
		return new ResetSecretResponse(this.service.resetSecret(id));
	}

	@PostMapping("/{id}/disable")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Désactiver un client",
			description = "Le client ne peut plus s'authentifier ni créer de liaisons. Les liaisons existantes restent actives.")
	@ApiResponse(responseCode = "204", description = "Client désactivé")
	@ApiResponse(responseCode = "404", description = "INTEGRATION_CLIENT_NOT_FOUND",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	void disable(@PathVariable UUID id) {
		this.service.disable(id);
	}

	@PostMapping("/{id}/enable")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Réactiver un client")
	@ApiResponse(responseCode = "204", description = "Client réactivé")
	@ApiResponse(responseCode = "404", description = "INTEGRATION_CLIENT_NOT_FOUND",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	void enable(@PathVariable UUID id) {
		this.service.enable(id);
	}

}
