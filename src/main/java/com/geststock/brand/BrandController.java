package com.geststock.brand;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ResponseStatus;
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
import com.geststock.brand.dto.BrandRequest;
import com.geststock.brand.dto.BrandResponse;
import com.geststock.common.api.ActiveStatus;
import com.geststock.common.api.OkResponse;
import com.geststock.common.api.PageQuery;
import com.geststock.common.api.PageResponse;
import com.geststock.common.api.SetActiveRequest;
import com.geststock.common.security.Permissions;
import com.geststock.common.security.RequiresPermission;

/** Marques de produits. */
@RestController
@RequestMapping("/api/brands")
@Tag(name = "Catalogue — marques")
class BrandController {

	private final BrandService service;

	BrandController(BrandService service) {
		this.service = service;
	}

	@GetMapping
	@RequiresPermission(Permissions.BRAND_READ)
	@Operation(summary = "Lister les marques", description = "Actives par défaut (status = ACTIVE, INACTIVE ou ALL).")
	ResponseEntity<PageResponse<BrandResponse>> list(@RequestParam(required = false) String q,
			@RequestParam(required = false) ActiveStatus status, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size,
			@Parameter(description = "name | createdAt, asc | desc") @RequestParam(required = false) List<String> sort) {
		PageQuery query = new PageQuery(page, size, sort);
		return PageResponse.toResponse(this.service.search(q, status, query), query);
	}

	@GetMapping("/{id}")
	@RequiresPermission(Permissions.BRAND_READ)
	@Operation(summary = "Consulter une marque")
	BrandResponse get(@PathVariable UUID id) {
		return this.service.get(id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Audited(action = "CREATE", entityType = "BRAND", idParam = "")
	@RequiresPermission(Permissions.BRAND_MANAGE)
	@Operation(summary = "Créer une marque")
	ResponseEntity<BrandResponse> create(@Valid @RequestBody BrandRequest request) {
		BrandResponse created = this.service.create(request);
		return ResponseEntity.created(URI.create("/api/brands/" + created.id())).body(created);
	}

	@PutMapping("/{id}")
	@Audited(action = "UPDATE", entityType = "BRAND")
	@RequiresPermission(Permissions.BRAND_MANAGE)
	@Operation(summary = "Renommer une marque")
	BrandResponse update(@PathVariable UUID id, @Valid @RequestBody BrandRequest request) {
		return this.service.update(id, request);
	}

	@PatchMapping("/{id}/active")
	@Audited(action = "SET_ACTIVE", entityType = "BRAND")
	@RequiresPermission(Permissions.BRAND_MANAGE)
	@Operation(summary = "Activer ou désactiver une marque")
	BrandResponse setActive(@PathVariable UUID id, @Valid @RequestBody SetActiveRequest request) {
		return this.service.setActive(id, request.active());
	}

	@DeleteMapping("/{id}")
	@Audited(action = "DEACTIVATE", entityType = "BRAND")
	@RequiresPermission(Permissions.BRAND_MANAGE)
	@Operation(summary = "Supprimer une marque (désactivation)")
	OkResponse delete(@PathVariable UUID id) {
		this.service.setActive(id, false);
		return OkResponse.success();
	}

}
