package com.geststock.category;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

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
import com.geststock.category.dto.CategoryRequest;
import com.geststock.category.dto.CategoryResponse;
import com.geststock.common.api.ActiveStatus;
import com.geststock.common.api.OkResponse;
import com.geststock.common.api.PageQuery;
import com.geststock.common.api.PageResponse;
import com.geststock.common.api.SetActiveRequest;
import com.geststock.common.security.Permissions;
import com.geststock.common.security.RequiresPermission;

/** Catégories de produits. {@code GET /api/categories} respecte le contrat du front. */
@RestController
@RequestMapping("/api/categories")
@Tag(name = "Catalogue — catégories")
class CategoryController {

	private final CategoryService service;

	CategoryController(CategoryService service) {
		this.service = service;
	}

	@GetMapping
	@RequiresPermission(Permissions.CATEGORY_READ)
	@Operation(summary = "Lister les catégories", description = "Actives par défaut (status = ACTIVE, INACTIVE ou ALL).")
	ResponseEntity<PageResponse<CategoryResponse>> list(@RequestParam(required = false) String q,
			@RequestParam(required = false) ActiveStatus status, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size,
			@Parameter(description = "name | createdAt, asc | desc") @RequestParam(required = false) List<String> sort) {
		PageQuery query = new PageQuery(page, size, sort);
		return PageResponse.toResponse(this.service.search(q, status, query), query);
	}

	@GetMapping("/{id}")
	@RequiresPermission(Permissions.CATEGORY_READ)
	@Operation(summary = "Consulter une catégorie")
	CategoryResponse get(@PathVariable UUID id) {
		return this.service.get(id);
	}

	@PostMapping
	@Audited(action = "CREATE", entityType = "CATEGORY", idParam = "")
	@RequiresPermission(Permissions.CATEGORY_MANAGE)
	@Operation(summary = "Créer une catégorie")
	ResponseEntity<CategoryResponse> create(@Valid @RequestBody CategoryRequest request) {
		CategoryResponse created = this.service.create(request);
		return ResponseEntity.created(URI.create("/api/categories/" + created.id())).body(created);
	}

	@PutMapping("/{id}")
	@Audited(action = "UPDATE", entityType = "CATEGORY")
	@RequiresPermission(Permissions.CATEGORY_MANAGE)
	@Operation(summary = "Modifier une catégorie")
	CategoryResponse update(@PathVariable UUID id, @Valid @RequestBody CategoryRequest request) {
		return this.service.update(id, request);
	}

	@PatchMapping("/{id}/active")
	@Audited(action = "SET_ACTIVE", entityType = "CATEGORY")
	@RequiresPermission(Permissions.CATEGORY_MANAGE)
	@Operation(summary = "Activer ou désactiver une catégorie")
	CategoryResponse setActive(@PathVariable UUID id, @Valid @RequestBody SetActiveRequest request) {
		return this.service.setActive(id, request.active());
	}

	@DeleteMapping("/{id}")
	@Audited(action = "DEACTIVATE", entityType = "CATEGORY")
	@RequiresPermission(Permissions.CATEGORY_MANAGE)
	@Operation(summary = "Supprimer une catégorie (désactivation)",
			description = "La catégorie est désactivée, jamais effacée : ses produits la conservent.")
	OkResponse delete(@PathVariable UUID id) {
		this.service.setActive(id, false);
		return OkResponse.success();
	}

}
