package com.geststock.purchase;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.common.api.PageQuery;
import com.geststock.common.api.PageResponse;
import com.geststock.common.security.Permissions;
import com.geststock.common.security.RequiresPermission;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.ForbiddenOperationException;
import com.geststock.purchase.dto.SupplierPurchaseHistoryResponse;
import com.geststock.warehouse.CurrentWarehouse;
import com.geststock.warehouse.WarehouseAccess;
import com.geststock.warehouse.WarehouseContext;

/**
 * Historique d'achats d'un fournisseur (contrat front {@code GET /api/suppliers/{id}/purchases}).
 * Servi par le module achats : le module fournisseur ne dépend pas des achats.
 */
@RestController
@RequestMapping("/api/suppliers/{supplierId}/purchases")
@Tag(name = "Fournisseurs")
class SupplierPurchaseHistoryController {

	private final PurchaseOrderService orders;

	private final WarehouseAccess warehouseAccess;

	SupplierPurchaseHistoryController(PurchaseOrderService orders, WarehouseAccess warehouseAccess) {
		this.orders = orders;
		this.warehouseAccess = warehouseAccess;
	}

	@GetMapping
	@RequiresPermission(Permissions.PURCHASE_READ)
	@Operation(summary = "Historique d'achats d'un fournisseur",
			description = "Une entrée par réception, de la plus récente à la plus ancienne, dans le dépôt courant "
					+ "(tous avec allWarehouses=true et warehouse:all).")
	ResponseEntity<PageResponse<SupplierPurchaseHistoryResponse>> history(@PathVariable UUID supplierId,
			@CurrentWarehouse WarehouseContext warehouse,
			@RequestParam(required = false) Boolean allWarehouses,
			@Parameter(description = "Recherche texte : n° de réception, n° de commande") @RequestParam(required = false) String q,
			@RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size,
			@Parameter(description = "purchaseDateIso | totalAmount, asc | desc") @RequestParam(
					required = false) List<String> sort) {
		UUID scope = warehouse.id();
		if (Boolean.TRUE.equals(allWarehouses)) {
			if (!this.warehouseAccess.seesAllWarehouses()) {
				throw new ForbiddenOperationException(ErrorCode.WAREHOUSE_ACCESS_DENIED,
						ErrorCode.WAREHOUSE_ACCESS_DENIED.defaultMessage());
			}
			scope = null;
		}
		PageQuery query = new PageQuery(page, size, sort);
		return PageResponse.toResponse(this.orders.supplierHistory(supplierId, scope, q, query), query);
	}

}
