package com.geststock.settings;

import java.io.IOException;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.geststock.audit.Audited;
import com.geststock.common.api.OkResponse;
import com.geststock.common.security.Permissions;
import com.geststock.common.security.RequiresPermission;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.InvalidRequestException;
import com.geststock.settings.InvoiceSettingsService.StoredLogo;
import com.geststock.settings.dto.InvoiceSettings;
import com.geststock.settings.dto.InvoiceSettingsResponse;
import com.geststock.settings.dto.InvoiceSettingsResponse.LogoInfo;

/**
 * Configuration des factures. Lecture ouverte à quiconque consulte ou enregistre des ventes (la
 * facture s'imprime avec) ; modification réservée à {@code settings:manage} (administrateur).
 */
@RestController
@RequestMapping("/api/settings/invoice")
@Tag(name = "Paramètres")
@PreAuthorize("hasAnyAuthority('sale:read', 'sale:create', 'settings:manage')")
class InvoiceSettingsController {

	private final InvoiceSettingsService service;

	InvoiceSettingsController(InvoiceSettingsService service) {
		this.service = service;
	}

	@GetMapping
	@Operation(summary = "Configuration de la facture",
			description = "Entreprise, en-tête, pied de page, apparence, et métadonnées du logo (null si aucun).")
	InvoiceSettingsResponse get() {
		return this.service.current();
	}

	@PutMapping
	@Audited(action = "UPDATE", entityType = InvoiceSettingsService.ENTITY_TYPE, idParam = "")
	@RequiresPermission(Permissions.SETTINGS_MANAGE)
	@Operation(summary = "Modifier la configuration de la facture",
			description = "Remplace toute la configuration (le logo se gère à part). Effet immédiat sur les factures "
					+ "affichées, imprimées et exportées, y compris la réimpression des ventes passées ; les montants, "
					+ "eux, viennent toujours de la vente.")
	InvoiceSettingsResponse update(@Valid @RequestBody InvoiceSettings request) {
		return this.service.update(request);
	}

	@PostMapping("/reset")
	@Audited(action = "RESET", entityType = InvoiceSettingsService.ENTITY_TYPE, idParam = "")
	@RequiresPermission(Permissions.SETTINGS_MANAGE)
	@Operation(summary = "Réinitialiser la configuration de la facture",
			description = "Revient aux valeurs par défaut ; le logo est conservé.")
	InvoiceSettingsResponse reset() {
		return this.service.reset();
	}

	@GetMapping("/logo")
	@Operation(summary = "Logo de l'entreprise",
			description = "Image PNG ou JPEG ; 404 LOGO_NOT_FOUND s'il n'y en a pas. ETag : empreinte SHA-256.")
	ResponseEntity<byte[]> logo() {
		StoredLogo logo = this.service.logo();
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(logo.contentType()))
			.cacheControl(CacheControl.noCache().cachePrivate())
			.eTag(logo.sha256())
			.header("X-Content-Type-Options", "nosniff")
			.body(logo.data());
	}

	@PostMapping("/logo")
	@Audited(action = "LOGO_UPLOAD", entityType = InvoiceSettingsService.ENTITY_TYPE, idParam = "")
	@RequiresPermission(Permissions.SETTINGS_MANAGE)
	@Operation(summary = "Ajouter ou remplacer le logo",
			description = "Champ multipart « file » : PNG ou JPEG, 2 Mo et 4000 × 4000 pixels au plus. Le type est "
					+ "vérifié sur le contenu du fichier, pas sur son nom. Requête multipart/form-data.")
	LogoInfo uploadLogo(@RequestPart(name = "file", required = false) MultipartFile file) {
		// Partie facultative pour la liaison : sans cela, une requête mal formée serait refusée
		// (400/415) avant le contrôle des droits, et un utilisateur non autorisé n'aurait pas son 403.
		if (file == null || file.isEmpty()) {
			throw new InvalidRequestException(ErrorCode.INVALID_LOGO, "Fichier requis : choisissez une image PNG ou JPEG.");
		}
		try {
			return this.service.uploadLogo(file.getBytes());
		}
		catch (IOException ex) {
			throw new InvalidRequestException(ErrorCode.INVALID_LOGO, "Fichier illisible : réessayez l'envoi.");
		}
	}

	@DeleteMapping("/logo")
	@Audited(action = "LOGO_DELETE", entityType = InvoiceSettingsService.ENTITY_TYPE, idParam = "")
	@RequiresPermission(Permissions.SETTINGS_MANAGE)
	@Operation(summary = "Retirer le logo")
	OkResponse deleteLogo() {
		this.service.deleteLogo();
		return OkResponse.success();
	}

}
