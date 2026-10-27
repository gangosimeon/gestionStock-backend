package com.geststock.product;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
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
import com.geststock.product.dto.ProductImageInfo;

/** Photo facultative d'un produit : lecture, ajout ou remplacement, retrait. */
@RestController
@RequestMapping("/api/products/{id}/image")
@Tag(name = "Catalogue — produits")
class ProductImageController {

	private final ProductImageService service;

	ProductImageController(ProductImageService service) {
		this.service = service;
	}

	@GetMapping
	@RequiresPermission(Permissions.PRODUCT_READ)
	@Operation(summary = "Photo d'un produit",
			description = "JPEG ; 404 PRODUCT_IMAGE_NOT_FOUND s'il n'y en a pas. ETag : empreinte SHA-256 (aussi "
					+ "renvoyée dans imageVersion du produit). Mise en cache privée : une nouvelle photo change d'empreinte.")
	ResponseEntity<byte[]> get(@PathVariable UUID id) {
		ProductImageService.StoredImage image = this.service.get(id);
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(image.contentType()))
			// Privée (données d'une entreprise, accès authentifié) ; revalidée par l'ETag au-delà d'un jour.
			.cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
			.eTag(image.sha256())
			.header("X-Content-Type-Options", "nosniff")
			.body(image.data());
	}

	@PutMapping
	@Audited(action = "IMAGE_UPDATE", entityType = "PRODUCT")
	@RequiresPermission(Permissions.PRODUCT_UPDATE)
	@Operation(summary = "Ajouter ou remplacer la photo",
			description = "Champ multipart « file » : photo JPEG ou PNG, 8 Mo et 8000 × 8000 pixels au plus. Le type est "
					+ "vérifié sur le contenu. Le serveur la réduit (512 px de côté au plus) et la ré-encode en JPEG, "
					+ "sans métadonnées. Requête multipart/form-data.")
	ProductImageInfo upload(@PathVariable UUID id, @RequestPart(name = "file", required = false) MultipartFile file) {
		// Partie facultative pour la liaison : un utilisateur non autorisé reçoit son 403 avant toute erreur de format.
		if (file == null || file.isEmpty()) {
			throw new InvalidRequestException(ErrorCode.INVALID_PRODUCT_IMAGE, "Fichier requis : choisissez une photo.");
		}
		try {
			return this.service.upload(id, file.getBytes());
		}
		catch (IOException ex) {
			throw new InvalidRequestException(ErrorCode.INVALID_PRODUCT_IMAGE, "Fichier illisible : réessayez l'envoi.");
		}
	}

	@DeleteMapping
	@Audited(action = "IMAGE_DELETE", entityType = "PRODUCT")
	@RequiresPermission(Permissions.PRODUCT_UPDATE)
	@Operation(summary = "Retirer la photo")
	OkResponse delete(@PathVariable UUID id) {
		this.service.delete(id);
		return OkResponse.success();
	}

}
