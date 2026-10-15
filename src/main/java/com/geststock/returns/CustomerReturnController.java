package com.geststock.returns;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.audit.Audited;
import com.geststock.common.api.PageQuery;
import com.geststock.common.api.PageResponse;
import com.geststock.common.security.Permissions;
import com.geststock.common.security.RequiresPermission;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.ForbiddenOperationException;
import com.geststock.returns.dto.CreateCustomerReturnRequest;
import com.geststock.returns.dto.CustomerReturnFilter;
import com.geststock.returns.dto.CustomerReturnResponse;
import com.geststock.warehouse.CurrentWarehouse;
import com.geststock.warehouse.WarehouseAccess;
import com.geststock.warehouse.WarehouseContext;

/** Retours clients et avoirs. */
@RestController
@Tag(name = "Retours clients")
class CustomerReturnController {

	private final CustomerReturnService returns;

	private final WarehouseAccess warehouseAccess;

	CustomerReturnController(CustomerReturnService returns, WarehouseAccess warehouseAccess) {
		this.returns = returns;
		this.warehouseAccess = warehouseAccess;
	}

	@PostMapping("/api/sales/{saleId}/returns")
	@ResponseStatus(HttpStatus.CREATED)
	@Audited(action = "CREATE", entityType = "CUSTOMER_RETURN", idParam = "")
	@RequiresPermission(Permissions.RETURN_CREATE)
	@Operation(summary = "Enregistrer un retour client",
			description = "Montants calculés au prix net de la vente ; jamais plus que vendu (retours précédents compris). "
					+ "Articles revendables remis en stock, défectueux non. Règlement : REFUND (sortie de la caisse "
					+ "ouverte de l'utilisateur, plafonné à ce que le client a payé) ou ACCOUNT_CREDIT (avoir imputé "
					+ "sur la dette du client). La vente d'origine est conservée et ne peut plus être annulée. "
					+ "Depuis le dépôt de la vente (409 WAREHOUSE_MISMATCH sinon).")
	ResponseEntity<CustomerReturnResponse> create(@PathVariable UUID saleId,
			@Valid @RequestBody CreateCustomerReturnRequest request, @CurrentWarehouse WarehouseContext warehouse) {
		CustomerReturnResponse created = this.returns.create(saleId, request, warehouse.id());
		return ResponseEntity.created(URI.create("/api/returns/" + created.id())).body(created);
	}

	@GetMapping("/api/sales/{saleId}/returns")
	@RequiresPermission(Permissions.SALE_READ)
	@Operation(summary = "Retours d'une vente")
	List<CustomerReturnResponse> forSale(@PathVariable UUID saleId) {
		return this.returns.forSale(saleId);
	}

	@GetMapping("/api/returns")
	@RequiresPermission(Permissions.SALE_READ)
	@Operation(summary = "Rechercher des retours",
			description = "Retours du dépôt courant, des plus récents aux plus anciens ; filtres customerId, settlement, "
					+ "from, to. allWarehouses=true exige warehouse:all.")
	ResponseEntity<PageResponse<CustomerReturnResponse>> list(@ParameterObject CustomerReturnFilter filter,
			@CurrentWarehouse WarehouseContext warehouse, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size,
			@Parameter(description = "createdAt | number | totalAmount, asc | desc") @RequestParam(
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
		return PageResponse.toResponse(this.returns.search(filter, scope, query), query);
	}

	@GetMapping("/api/returns/{id}")
	@RequiresPermission(Permissions.SALE_READ)
	@Operation(summary = "Consulter un retour")
	CustomerReturnResponse get(@PathVariable UUID id) {
		return this.returns.get(id);
	}

}
