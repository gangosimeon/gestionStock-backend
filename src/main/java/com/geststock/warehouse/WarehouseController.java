package com.geststock.warehouse;

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
import com.geststock.warehouse.dto.WarehouseFilter;
import com.geststock.warehouse.dto.WarehouseRequest;
import com.geststock.warehouse.dto.WarehouseResponse;

/**
 * Dépôts et magasins. {@code GET} et {@code POST /api/warehouses} respectent le contrat du
 * front ({@code {items, total}} avec {@code id, name} ; création avec le seul {@code name}).
 */
@RestController
@RequestMapping("/api/warehouses")
@Tag(name = "Dépôts")
class WarehouseController {

	private final WarehouseService service;

	WarehouseController(WarehouseService service) {
		this.service = service;
	}

	@GetMapping
	@RequiresPermission(Permissions.WAREHOUSE_READ)
	@Operation(summary = "Lister les dépôts accessibles",
			description = "Dépôts actifs du périmètre de l'utilisateur ; tous avec warehouse:all ou warehouse:manage "
					+ "(seuls ces derniers peuvent filtrer status = INACTIVE ou ALL). Sans page ni size : liste "
					+ "complète (compatibilité front).")
	ResponseEntity<PageResponse<WarehouseResponse>> list(@ParameterObject WarehouseFilter filter,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
			@Parameter(description = "name | code | type | createdAt, asc | desc") @RequestParam(
					required = false) List<String> sort) {
		PageQuery query = new PageQuery(page, size, sort);
		return PageResponse.toResponse(this.service.search(filter, query), query);
	}

	@GetMapping("/current")
	@RequiresPermission(Permissions.WAREHOUSE_READ)
	@Operation(summary = "Dépôt courant",
			description = "Dépôt retenu par le serveur pour l'en-tête X-Warehouse-Id envoyé (ou le dépôt par défaut).")
	WarehouseResponse current(@CurrentWarehouse WarehouseContext warehouse) {
		return this.service.get(warehouse.id());
	}

	@GetMapping("/{id}")
	@RequiresPermission(Permissions.WAREHOUSE_READ)
	@Operation(summary = "Consulter un dépôt")
	WarehouseResponse get(@PathVariable UUID id) {
		return this.service.get(id);
	}

	@PostMapping
	@RequiresPermission(Permissions.WAREHOUSE_MANAGE)
	@Operation(summary = "Créer un dépôt", description = "Code généré si absent (MAG-nn / DEP-nn), type STORE par défaut.")
	ResponseEntity<WarehouseResponse> create(@Valid @RequestBody WarehouseRequest request) {
		WarehouseResponse created = this.service.create(request);
		return ResponseEntity.created(URI.create("/api/warehouses/" + created.id())).body(created);
	}

	@PutMapping("/{id}")
	@RequiresPermission(Permissions.WAREHOUSE_MANAGE)
	@Operation(summary = "Modifier un dépôt")
	WarehouseResponse update(@PathVariable UUID id, @Valid @RequestBody WarehouseRequest request) {
		return this.service.update(id, request);
	}

	@PatchMapping("/{id}/active")
	@RequiresPermission(Permissions.WAREHOUSE_MANAGE)
	@Operation(summary = "Activer ou désactiver un dépôt", description = "Le dépôt principal ne peut pas être désactivé.")
	WarehouseResponse setActive(@PathVariable UUID id, @Valid @RequestBody SetActiveRequest request) {
		return this.service.setActive(id, request.active());
	}

	@PostMapping("/{id}/main")
	@RequiresPermission(Permissions.WAREHOUSE_MANAGE)
	@Operation(summary = "Désigner le dépôt principal")
	WarehouseResponse setMain(@PathVariable UUID id) {
		return this.service.setMain(id);
	}

	@DeleteMapping("/{id}")
	@RequiresPermission(Permissions.WAREHOUSE_MANAGE)
	@Operation(summary = "Supprimer un dépôt (désactivation)",
			description = "Le dépôt est désactivé, jamais effacé : stock et pièces le référencent.")
	OkResponse delete(@PathVariable UUID id) {
		this.service.setActive(id, false);
		return OkResponse.success();
	}

}
