package com.geststock.supplier;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springdoc.core.annotations.ParameterObject;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.common.api.OkResponse;
import com.geststock.common.api.PageQuery;
import com.geststock.common.api.PageResponse;
import com.geststock.common.api.SetActiveRequest;
import com.geststock.common.security.Permissions;
import com.geststock.common.security.RequiresPermission;
import com.geststock.supplier.dto.SupplierFilter;
import com.geststock.supplier.dto.SupplierRequest;
import com.geststock.supplier.dto.SupplierResponse;

/**
 * Fournisseurs. Chemins, verbes et formats du contrat du front ({@code /api/suppliers}).
 * L'historique des achats et les paiements d'un fournisseur
 * ({@code /api/suppliers/{id}/purchases|payments}) seront servis par le module achats.
 */
@RestController
@RequestMapping("/api/suppliers")
@Tag(name = "Fournisseurs")
class SupplierController {

	private final SupplierService service;

	SupplierController(SupplierService service) {
		this.service = service;
	}

	@GetMapping
	@RequiresPermission(Permissions.SUPPLIER_READ)
	@Operation(summary = "Rechercher des fournisseurs",
			description = "Actifs par défaut. Sans page ni size : liste complète (compatibilité front, plafond 5 000).")
	ResponseEntity<PageResponse<SupplierResponse>> list(@ParameterObject SupplierFilter filter,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
			@Parameter(description = "name | code | deliveryLeadTimeDays | createdAt, asc | desc") @RequestParam(
					required = false) List<String> sort) {
		PageQuery query = new PageQuery(page, size, sort);
		return PageResponse.toResponse(this.service.search(filter, query), query);
	}

	@GetMapping("/{id}")
	@RequiresPermission(Permissions.SUPPLIER_READ)
	@Operation(summary = "Consulter un fournisseur")
	SupplierResponse get(@PathVariable UUID id) {
		return this.service.get(id);
	}

	@PostMapping
	@RequiresPermission(Permissions.SUPPLIER_CREATE)
	@Operation(summary = "Créer un fournisseur", description = "Code généré (FOU-0001) s'il n'est pas fourni.")
	ResponseEntity<SupplierResponse> create(@Valid @RequestBody SupplierRequest request) {
		SupplierResponse created = this.service.create(request);
		return ResponseEntity.created(URI.create("/api/suppliers/" + created.id())).body(created);
	}

	@PutMapping("/{id}")
	@RequiresPermission(Permissions.SUPPLIER_UPDATE)
	@Operation(summary = "Modifier un fournisseur", description = "Les champs inconnus du front (code, contactName, taxId, "
			+ "paymentTermsDays, note) sont conservés s'ils sont absents ; une chaîne vide les efface.")
	SupplierResponse update(@PathVariable UUID id, @Valid @RequestBody SupplierRequest request) {
		return this.service.update(id, request);
	}

	@PatchMapping("/{id}/active")
	@RequiresPermission(Permissions.SUPPLIER_DELETE)
	@Operation(summary = "Activer ou désactiver un fournisseur")
	SupplierResponse setActive(@PathVariable UUID id, @Valid @RequestBody SetActiveRequest request) {
		return this.service.setActive(id, request.active());
	}

	@DeleteMapping("/{id}")
	@RequiresPermission(Permissions.SUPPLIER_DELETE)
	@Operation(summary = "Supprimer un fournisseur (désactivation)",
			description = "Le fournisseur est désactivé, jamais effacé : produits et pièces d'achat le référencent.")
	OkResponse delete(@PathVariable UUID id) {
		this.service.setActive(id, false);
		return OkResponse.success();
	}

}
