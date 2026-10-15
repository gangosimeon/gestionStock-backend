package com.geststock.inventory;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springdoc.core.annotations.ParameterObject;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.audit.Audited;
import com.geststock.common.api.PageQuery;
import com.geststock.common.api.PageResponse;
import com.geststock.common.security.Permissions;
import com.geststock.common.security.RequiresPermission;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.ForbiddenOperationException;
import com.geststock.inventory.dto.CreateInventoryRequest;
import com.geststock.inventory.dto.InventoryCommentRequest;
import com.geststock.inventory.dto.InventoryCountsRequest;
import com.geststock.inventory.dto.InventoryFilter;
import com.geststock.inventory.dto.InventoryProductsRequest;
import com.geststock.inventory.dto.InventoryResponse;
import com.geststock.warehouse.CurrentWarehouse;
import com.geststock.warehouse.WarehouseAccess;
import com.geststock.warehouse.WarehouseContext;

/**
 * Inventaires : workflow complet. Les chemins historiques du front
 * ({@code /api/inventory/sessions}) sont servis par {@link LegacyInventoryController}.
 */
@RestController
@RequestMapping("/api/inventories")
@Tag(name = "Inventaires")
class InventoryController {

	private final InventoryService inventories;

	private final WarehouseAccess warehouseAccess;

	InventoryController(InventoryService inventories, WarehouseAccess warehouseAccess) {
		this.inventories = inventories;
		this.warehouseAccess = warehouseAccess;
	}

	@GetMapping
	@RequiresPermission(Permissions.INVENTORY_READ)
	@Operation(summary = "Rechercher des inventaires",
			description = "Inventaires du dépôt courant, plus récents d'abord ; filtres status, from, to. "
					+ "allWarehouses=true exige warehouse:all.")
	ResponseEntity<PageResponse<InventoryResponse>> list(@ParameterObject InventoryFilter filter,
			@CurrentWarehouse WarehouseContext warehouse, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size,
			@Parameter(description = "createdAt | number | validatedAt, asc | desc") @RequestParam(
					required = false) List<String> sort) {
		return search(this.inventories, this.warehouseAccess, filter, warehouse, page, size, sort);
	}

	@GetMapping("/{id}")
	@RequiresPermission(Permissions.INVENTORY_READ)
	@Operation(summary = "Consulter un inventaire")
	InventoryResponse get(@PathVariable UUID id) {
		return this.inventories.get(id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Audited(action = "CREATE", entityType = "INVENTORY_SESSION", idParam = "")
	@RequiresPermission(Permissions.INVENTORY_CREATE)
	@Operation(summary = "Créer un inventaire (brouillon)",
			description = "Périmètre FULL (produits actifs), CATEGORY (categoryId) ou SELECTION (productIds). "
					+ "Dépôt courant sauf warehouseId ; un seul inventaire ouvert par dépôt.")
	ResponseEntity<InventoryResponse> create(@Valid @RequestBody CreateInventoryRequest request,
			@CurrentWarehouse WarehouseContext warehouse) {
		InventoryResponse created = this.inventories.create(request, warehouse.id());
		return ResponseEntity.created(URI.create("/api/inventories/" + created.id())).body(created);
	}

	@PostMapping("/{id}/products")
	@Audited(action = "ADD_PRODUCTS", entityType = "INVENTORY_SESSION")
	@RequiresPermission(Permissions.INVENTORY_CREATE)
	@Operation(summary = "Ajouter des produits (brouillon)")
	InventoryResponse addProducts(@PathVariable UUID id, @Valid @RequestBody InventoryProductsRequest request,
			@CurrentWarehouse WarehouseContext warehouse) {
		return this.inventories.addProducts(id, request.productIds(), warehouse.id());
	}

	@DeleteMapping("/{id}/products/{productId}")
	@Audited(action = "REMOVE_PRODUCT", entityType = "INVENTORY_SESSION")
	@RequiresPermission(Permissions.INVENTORY_CREATE)
	@Operation(summary = "Retirer un produit (brouillon)")
	InventoryResponse removeProduct(@PathVariable UUID id, @PathVariable UUID productId, @CurrentWarehouse WarehouseContext warehouse) {
		return this.inventories.removeProduct(id, productId, warehouse.id());
	}

	@PostMapping("/{id}/start")
	@Audited(action = "START", entityType = "INVENTORY_SESSION")
	@RequiresPermission(Permissions.INVENTORY_CREATE)
	@Operation(summary = "Démarrer", description = "DRAFT → IN_PROGRESS : périmètre figé.")
	InventoryResponse start(@PathVariable UUID id, @CurrentWarehouse WarehouseContext warehouse) {
		return this.inventories.start(id, warehouse.id());
	}

	@PutMapping("/{id}/counts")
	@Audited(action = "COUNT", entityType = "INVENTORY_SESSION")
	@RequiresPermission(Permissions.INVENTORY_CREATE)
	@Operation(summary = "Saisir des comptages",
			description = "Quantité théorique relevée par le serveur au même instant ; recomptage possible jusqu'à la "
					+ "soumission.")
	InventoryResponse counts(@PathVariable UUID id, @Valid @RequestBody InventoryCountsRequest request,
			@CurrentWarehouse WarehouseContext warehouse) {
		return this.inventories.recordCounts(id, request.counts(), warehouse.id());
	}

	@PostMapping("/{id}/submit")
	@Audited(action = "SUBMIT", entityType = "INVENTORY_SESSION")
	@RequiresPermission(Permissions.INVENTORY_CREATE)
	@Operation(summary = "Soumettre à validation", description = "COUNTING → VALIDATION : tous les produits comptés.")
	InventoryResponse submit(@PathVariable UUID id, @CurrentWarehouse WarehouseContext warehouse) {
		return this.inventories.submit(id, warehouse.id());
	}

	@PostMapping("/{id}/reopen")
	@Audited(action = "REOPEN", entityType = "INVENTORY_SESSION")
	@RequiresPermission(Permissions.INVENTORY_VALIDATE)
	@Operation(summary = "Renvoyer au comptage", description = "VALIDATION → COUNTING (recomptage demandé).")
	InventoryResponse reopen(@PathVariable UUID id,
			@Valid @RequestBody(required = false) InventoryCommentRequest request, @CurrentWarehouse WarehouseContext warehouse) {
		return this.inventories.reopen(id, request != null ? request.comment() : null, warehouse.id());
	}

	@PostMapping("/{id}/validate")
	@Audited(action = "VALIDATE", entityType = "INVENTORY_SESSION")
	@RequiresPermission(Permissions.INVENTORY_VALIDATE)
	@Operation(summary = "Valider",
			description = "VALIDATION → COMPLETED : chaque écart appliqué au stock (mouvement INVENTORY), en une transaction.")
	InventoryResponse validate(@PathVariable UUID id,
			@Valid @RequestBody(required = false) InventoryCommentRequest request, @CurrentWarehouse WarehouseContext warehouse) {
		return this.inventories.validate(id, request != null ? request.comment() : null, warehouse.id());
	}

	@PostMapping("/{id}/cancel")
	@Audited(action = "CANCEL", entityType = "INVENTORY_SESSION")
	@RequiresPermission(Permissions.INVENTORY_CREATE)
	@Operation(summary = "Annuler", description = "Avant validation uniquement ; aucun effet sur le stock.")
	InventoryResponse cancel(@PathVariable UUID id, @Valid @RequestBody(required = false) InventoryCommentRequest request,
			@CurrentWarehouse WarehouseContext warehouse) {
		return this.inventories.cancel(id, request != null ? request.comment() : null, warehouse.id());
	}

	static ResponseEntity<PageResponse<InventoryResponse>> search(InventoryService inventories,
			WarehouseAccess warehouseAccess, InventoryFilter filter, WarehouseContext warehouse, Integer page,
			Integer size, List<String> sort) {
		UUID scope = warehouse.id();
		if (Boolean.TRUE.equals(filter.allWarehouses())) {
			if (!warehouseAccess.seesAllWarehouses()) {
				throw new ForbiddenOperationException(ErrorCode.WAREHOUSE_ACCESS_DENIED,
						ErrorCode.WAREHOUSE_ACCESS_DENIED.defaultMessage());
			}
			scope = null;
		}
		PageQuery query = new PageQuery(page, size, sort);
		return PageResponse.toResponse(inventories.search(filter, scope, query), query);
	}

}
