package com.geststock.stock;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.audit.Audited;
import com.geststock.common.api.PageQuery;
import com.geststock.common.api.PageResponse;
import com.geststock.common.security.Permissions;
import com.geststock.common.security.RequiresPermission;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.ForbiddenOperationException;
import com.geststock.stock.dto.ReturnStockTransferRequest;
import com.geststock.stock.dto.StockMovementResponse;
import com.geststock.stock.dto.StockTransferFilter;
import com.geststock.stock.dto.StockTransferResponse;
import com.geststock.stock.dto.TransferStockRequest;
import com.geststock.stock.dto.TransferStockResponse;
import com.geststock.warehouse.CurrentWarehouse;
import com.geststock.warehouse.WarehouseAccess;
import com.geststock.warehouse.WarehouseContext;

/**
 * Transferts de stock entre dépôts : création (chemin du contrat front :
 * {@code /api/warehouses/transfer}), historique des transferts envoyés et reçus, reçu et retour
 * au dépôt d'origine.
 */
@RestController
@Tag(name = "Stock")
class StockTransferController {

	private final StockTransferService transfers;

	private final StockQueryService queries;

	private final WarehouseAccess warehouseAccess;

	StockTransferController(StockTransferService transfers, StockQueryService queries,
			WarehouseAccess warehouseAccess) {
		this.transfers = transfers;
		this.queries = queries;
		this.warehouseAccess = warehouseAccess;
	}

	@PostMapping("/api/warehouses/transfer")
	@RequiresPermission(Permissions.STOCK_TRANSFER)
	@Audited(action = "TRANSFER", entityType = "WAREHOUSE_TRANSFER", idParam = "")
	@Operation(summary = "Transférer du stock entre deux dépôts",
			description = "Un ou plusieurs produits (items, comme une vente ; productId + quantity pour un seul) : "
					+ "sortie du dépôt source et entrée dans le dépôt destination, atomiques, sous une pièce numérotée "
					+ "(TRF-AAAA-nnnnnn, reçu). Un produit au plus une fois (400 STOCK_TRANSFER_DUPLICATE_PRODUCT). Le "
					+ "dépôt source est le dépôt courant (409 WAREHOUSE_MISMATCH sinon) ; la destination doit être active "
					+ "et accessible à l'utilisateur.")
	TransferStockResponse transfer(@Valid @RequestBody TransferStockRequest request,
			@CurrentWarehouse WarehouseContext warehouse) {
		this.warehouseAccess.requireCurrent(request.fromWarehouseId(), warehouse.id());
		this.warehouseAccess.requireAccessible(request.fromWarehouseId());
		this.warehouseAccess.requireAccessible(request.toWarehouseId());
		StockTransferService.Created created = this.transfers.create(request.fromWarehouseId(),
				request.toWarehouseId(), request.requestedItems(), request.note());
		List<StockMovementResponse> responses = this.queries.toResponses(created.movements());
		return new TransferStockResponse(true, created.transfer().id(), responses.get(0), responses.get(1), responses,
				created.transfer());
	}

	@GetMapping("/api/stock/transfers")
	@RequiresPermission(Permissions.STOCK_READ)
	@Operation(summary = "Historique des transferts",
			description = "Transferts envoyés par le dépôt courant ou reçus par lui (direction=SENT | RECEIVED), des "
					+ "plus récents aux plus anciens ; filtres q (numéro, produit, note), status, productId, from, to "
					+ "(date du transfert). allWarehouses=true exige warehouse:all.")
	ResponseEntity<PageResponse<StockTransferResponse>> list(@ParameterObject StockTransferFilter filter,
			@CurrentWarehouse WarehouseContext warehouse, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size,
			@Parameter(description = "createdAt | number, asc | desc") @RequestParam(
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
		return PageResponse.toResponse(this.transfers.search(filter, scope, query), query);
	}

	@GetMapping("/api/stock/transfers/{id}")
	@RequiresPermission(Permissions.STOCK_READ)
	@Operation(summary = "Consulter un transfert (reçu)")
	StockTransferResponse get(@PathVariable UUID id) {
		return this.transfers.get(id);
	}

	@PostMapping("/api/stock/transfers/{id}/return")
	@RequiresPermission(Permissions.STOCK_TRANSFER)
	@Audited(action = "RETURN", entityType = "WAREHOUSE_TRANSFER")
	@Operation(summary = "Retourner un transfert au dépôt d'origine",
			description = "Les quantités transférées (toutes les lignes) sortent du dépôt de destination et reviennent "
					+ "au dépôt d'origine, une seule fois (409 STOCK_TRANSFER_ALREADY_RETURNED). Depuis l'un des deux "
					+ "dépôts (409 WAREHOUSE_MISMATCH sinon), avec accès aux deux ; 422 STOCK_INSUFFICIENT si la "
					+ "destination n'a plus l'un des produits.")
	StockTransferResponse returnToOrigin(@PathVariable UUID id,
			@Valid @RequestBody(required = false) ReturnStockTransferRequest request,
			@CurrentWarehouse WarehouseContext warehouse) {
		return this.transfers.returnToOrigin(id, request != null ? request.reason() : null, warehouse.id());
	}

}
