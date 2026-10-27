package com.geststock.platform.provisioning;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.exception.ApiError;

/** Mise à jour du schéma des bases des entreprises : état et relance (Super Admin). */
@RestController
@RequestMapping("/api/platform/schema-upgrades")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Tag(name = "Plateforme — mises à jour des bases", description = "Migrations des bases des entreprises après un déploiement (Super Admin)")
@ApiResponse(responseCode = "403", description = "Réservé au Super Admin",
		content = @Content(schema = @Schema(implementation = ApiError.class)))
class SchemaUpgradeController {

	private final SchemaUpgradeService service;

	SchemaUpgradeController(SchemaUpgradeService service) {
		this.service = service;
	}

	@GetMapping
	@Operation(summary = "État de la mise à jour des bases",
			description = "Pour chaque base vérifiée depuis le démarrage de l'instance : à jour, en cours de mise à jour ou en échec (message sans secret).")
	@ApiResponse(responseCode = "200", description = "OK")
	SchemaUpgradeService.Status status() {
		return this.service.status();
	}

	@PostMapping
	@Operation(summary = "Relancer la mise à jour de toutes les bases",
			description = "Vérifie chaque base active et applique les migrations manquantes, en arrière-plan. "
					+ "Une entreprise est refusée (503) le temps de sa mise à jour, ou tant qu'elle est en échec.")
	@ApiResponse(responseCode = "202", description = "Mise à jour lancée")
	@ApiResponse(responseCode = "409", description = "Une mise à jour est déjà en cours")
	ResponseEntity<SchemaUpgradeService.Status> start() {
		boolean started = this.service.startByAdmin();
		return ResponseEntity.status(started ? HttpStatus.ACCEPTED : HttpStatus.CONFLICT).body(this.service.status());
	}

}
