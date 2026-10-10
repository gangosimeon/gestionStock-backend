package com.geststock.sale;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springdoc.core.annotations.ParameterObject;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
import com.geststock.sale.dto.CancelSaleRequest;
import com.geststock.sale.dto.CreateSaleRequest;
import com.geststock.sale.dto.SaleFilter;
import com.geststock.sale.dto.SaleResponse;
import com.geststock.warehouse.CurrentWarehouse;
import com.geststock.warehouse.WarehouseAccess;
import com.geststock.warehouse.WarehouseContext;

/** Ventes. Chemins et formats du contrat du front ({@code /api/sales}). */
@RestController
@RequestMapping("/api/sales")
@Tag(name = "Ventes")
class SaleController {

	private final SaleService sales;

	private final WarehouseAccess warehouseAccess;

	SaleController(SaleService sales, WarehouseAccess warehouseAccess) {
		this.sales = sales;
		this.warehouseAccess = warehouseAccess;
	}

	@GetMapping
	@RequiresPermission(Permissions.SALE_READ)
	@Operation(summary = "Rechercher des ventes",
			description = "Ventes du dépôt courant, des plus récentes aux plus anciennes ; filtres q, status, customerId, "
					+ "paymentMethod, from, to. Sans page ni size : liste complète (compatibilité front, plafond 5 000). "
					+ "allWarehouses=true exige warehouse:all.")
	ResponseEntity<PageResponse<SaleResponse>> list(@ParameterObject SaleFilter filter,
			@CurrentWarehouse WarehouseContext warehouse, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size,
			@Parameter(description = "createdAt | number | total | customerName, asc | desc") @RequestParam(
					required = false) List<String> sort) {
		UUID scope = warehouse.id();
		if (Boolean.TRUE.equals(filter.allWarehouses())) {
			if (!this.warehouseAccess.seesAllWarehouses()) {
				throw new ForbiddenOperationException(ErrorCode.WAREHOUSE_ACCESS_DENIED,
						ErrorCode.WAREHOUSE_ACCESS_DENIED.defaultMessage());
			}
			scope = null;
		}
		PageQuery query = new PageQuery(page, size, sort);
		return PageResponse.toResponse(this.sales.search(filter, scope, query), query);
	}

	@GetMapping("/{id}")
	@RequiresPermission(Permissions.SALE_READ)
	@Operation(summary = "Consulter une vente")
	SaleResponse get(@PathVariable UUID id) {
		return this.sales.get(id);
	}

	@PostMapping
	@Audited(action = "CREATE", entityType = "SALE", idParam = "")
	@RequiresPermission(Permissions.SALE_CREATE)
	@Operation(summary = "Enregistrer une vente",
			description = "Prix, coûts et montants calculés par le serveur (prix envoyés ignorés). Sorties de stock, "
					+ "créance client et audit dans la même transaction. Dépôt courant sauf warehouseId explicite.")
	ResponseEntity<SaleResponse> create(@Valid @RequestBody CreateSaleRequest request,
			@CurrentWarehouse WarehouseContext warehouse) {
		SaleResponse created = this.sales.create(request, warehouse.id());
		return ResponseEntity.created(URI.create("/api/sales/" + created.id())).body(created);
	}

	@PostMapping("/{id}/cancel")
	@Audited(action = "CANCEL", entityType = "SALE")
	@RequiresPermission(Permissions.SALE_CANCEL)
	@Operation(summary = "Annuler une vente",
			description = "Remise en stock et annulation de la créance, motif obligatoire. Refusée si le client a déjà "
					+ "réglé le crédit (passer par un retour).")
	SaleResponse cancel(@PathVariable UUID id, @Valid @RequestBody CancelSaleRequest request) {
		return this.sales.cancel(id, request);
	}

}
