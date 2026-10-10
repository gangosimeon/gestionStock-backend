package com.geststock.inventory;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springdoc.core.annotations.ParameterObject;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.audit.Audited;
import com.geststock.common.api.PageResponse;
import com.geststock.common.security.Permissions;
import com.geststock.common.security.RequiresPermission;
import com.geststock.inventory.dto.InventoryFilter;
import com.geststock.inventory.dto.InventoryResponse;
import com.geststock.inventory.dto.LegacyInventoryRequest;
import com.geststock.warehouse.CurrentWarehouse;
import com.geststock.warehouse.WarehouseAccess;
import com.geststock.warehouse.WarehouseContext;

/**
 * Chemins historiques du front ({@code /api/inventory/sessions}). Le {@code POST} enchaîne le
 * workflow en une transaction ; il ne contourne aucune règle : sans {@code inventory:validate},
 * l'inventaire reste en attente de validation.
 */
@RestController
@RequestMapping("/api/inventory/sessions")
@Tag(name = "Inventaires")
class LegacyInventoryController {

	private final InventoryService inventories;

	private final WarehouseAccess warehouseAccess;

	LegacyInventoryController(InventoryService inventories, WarehouseAccess warehouseAccess) {
		this.inventories = inventories;
		this.warehouseAccess = warehouseAccess;
	}

	@GetMapping
	@RequiresPermission(Permissions.INVENTORY_READ)
	@Operation(summary = "Lister les inventaires (contrat front)")
	ResponseEntity<PageResponse<InventoryResponse>> list(@ParameterObject InventoryFilter filter,
			@CurrentWarehouse WarehouseContext warehouse, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size, @RequestParam(required = false) List<String> sort) {
		return InventoryController.search(this.inventories, this.warehouseAccess, filter, warehouse, page, size, sort);
	}

	@GetMapping("/{id}")
	@RequiresPermission(Permissions.INVENTORY_READ)
	@Operation(summary = "Consulter un inventaire (contrat front)")
	InventoryResponse get(@PathVariable UUID id) {
		return this.inventories.get(id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Audited(action = "CREATE", entityType = "INVENTORY_SESSION", idParam = "")
	@RequiresPermission(Permissions.INVENTORY_CREATE)
	@Operation(summary = "Inventaire en une fois (contrat front)",
			description = "Sélection, comptage et soumission en une transaction ; validation immédiate avec "
					+ "inventory:validate, sinon l'inventaire attend un valideur.")
	ResponseEntity<InventoryResponse> create(@Valid @RequestBody LegacyInventoryRequest request,
			@CurrentWarehouse WarehouseContext warehouse) {
		InventoryResponse created = this.inventories.createAndCount(request, warehouse.id());
		return ResponseEntity.created(URI.create("/api/inventories/" + created.id())).body(created);
	}

}
