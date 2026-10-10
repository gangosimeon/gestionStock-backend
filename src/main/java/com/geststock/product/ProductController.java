package com.geststock.product;

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

import com.geststock.audit.Audited;
import com.geststock.common.api.OkResponse;
import com.geststock.common.api.PageQuery;
import com.geststock.common.api.PageResponse;
import com.geststock.common.api.SetActiveRequest;
import com.geststock.common.security.Permissions;
import com.geststock.common.security.RequiresPermission;
import com.geststock.product.dto.ProductFilter;
import com.geststock.product.dto.ProductRequest;
import com.geststock.product.dto.ProductResponse;
import com.geststock.warehouse.CurrentWarehouse;
import com.geststock.warehouse.WarehouseContext;

/**
 * Catalogue produits. Chemins et formats du contrat du front ({@code /api/products}),
 * sauf que le stock n'est plus modifiable ici (voir {@link ProductRequest}). Le stock affiché
 * est celui du dépôt courant, résolu et contrôlé par le serveur ({@link CurrentWarehouse}).
 */
@RestController
@RequestMapping("/api/products")
@Tag(name = "Catalogue — produits")
class ProductController {

	private final ProductService service;

	ProductController(ProductService service) {
		this.service = service;
	}

	@GetMapping
	@RequiresPermission(Permissions.PRODUCT_READ)
	@Operation(summary = "Rechercher des produits",
			description = "Actifs par défaut. Sans page ni size : liste complète (compatibilité front, plafond 5 000). "
					+ "Tris : sku, name, categoryName, purchasePrice, retailPrice, wholesalePrice, createdAt, updatedAt.")
	ResponseEntity<PageResponse<ProductResponse>> list(@ParameterObject ProductFilter filter,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
			@Parameter(description = "champ,asc|desc (répétable)") @RequestParam(required = false) List<String> sort,
			@CurrentWarehouse WarehouseContext warehouse) {
		PageQuery query = new PageQuery(page, size, sort);
		return PageResponse.toResponse(this.service.search(filter, query, warehouse.id()), query);
	}

	@GetMapping("/{id}")
	@RequiresPermission(Permissions.PRODUCT_READ)
	@Operation(summary = "Consulter un produit")
	ProductResponse get(@PathVariable UUID id, @CurrentWarehouse WarehouseContext warehouse) {
		return this.service.get(id, warehouse.id());
	}

	@PostMapping
	@Audited(action = "CREATE", entityType = "PRODUCT", idParam = "")
	@RequiresPermission(Permissions.PRODUCT_CREATE)
	@Operation(summary = "Créer un produit", description = "Le stock initial ne se saisit pas ici : il s'enregistre par "
			+ "un mouvement de stock. Un champ stockQuantity éventuellement envoyé est ignoré.")
	ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request,
			@CurrentWarehouse WarehouseContext warehouse) {
		ProductResponse created = this.service.create(request, warehouse.id());
		return ResponseEntity.created(URI.create("/api/products/" + created.id())).body(created);
	}

	@PutMapping("/{id}")
	@Audited(action = "UPDATE", entityType = "PRODUCT")
	@RequiresPermission(Permissions.PRODUCT_UPDATE)
	@Operation(summary = "Modifier un produit", description = "Ne modifie jamais le stock (stockQuantity ignoré).")
	ProductResponse update(@PathVariable UUID id, @Valid @RequestBody ProductRequest request,
			@CurrentWarehouse WarehouseContext warehouse) {
		return this.service.update(id, request, warehouse.id());
	}

	@PatchMapping("/{id}/active")
	@Audited(action = "SET_ACTIVE", entityType = "PRODUCT")
	@RequiresPermission(Permissions.PRODUCT_DELETE)
	@Operation(summary = "Activer ou désactiver un produit")
	ProductResponse setActive(@PathVariable UUID id, @Valid @RequestBody SetActiveRequest request,
			@CurrentWarehouse WarehouseContext warehouse) {
		return this.service.setActive(id, request.active(), warehouse.id());
	}

	@DeleteMapping("/{id}")
	@Audited(action = "DEACTIVATE", entityType = "PRODUCT")
	@RequiresPermission(Permissions.PRODUCT_DELETE)
	@Operation(summary = "Supprimer un produit (désactivation)",
			description = "Le produit est désactivé, jamais effacé : ventes et mouvements le référencent.")
	OkResponse delete(@PathVariable UUID id) {
		this.service.setActive(id, false, null);
		return OkResponse.success();
	}

}
