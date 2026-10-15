package com.geststock.stock;

import java.util.List;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.audit.Audited;
import com.geststock.common.security.Permissions;
import com.geststock.common.security.RequiresPermission;
import com.geststock.stock.dto.StockMovementResponse;
import com.geststock.stock.dto.TransferStockRequest;
import com.geststock.stock.dto.TransferStockResponse;
import com.geststock.warehouse.CurrentWarehouse;
import com.geststock.warehouse.WarehouseAccess;
import com.geststock.warehouse.WarehouseContext;

/** Transfert de stock entre dépôts (chemin du contrat front : {@code /api/warehouses/transfer}). */
@RestController
@Tag(name = "Stock")
class StockTransferController {

	private final StockService stock;

	private final StockQueryService queries;

	private final WarehouseAccess warehouseAccess;

	StockTransferController(StockService stock, StockQueryService queries, WarehouseAccess warehouseAccess) {
		this.stock = stock;
		this.queries = queries;
		this.warehouseAccess = warehouseAccess;
	}

	@PostMapping("/api/warehouses/transfer")
	@RequiresPermission(Permissions.STOCK_TRANSFER)
	@Audited(action = "TRANSFER", entityType = "WAREHOUSE_TRANSFER", idParam = "")
	@Operation(summary = "Transférer du stock entre deux dépôts",
			description = "Sortie du dépôt source et entrée dans le dépôt destination, atomiques. Le dépôt source est "
					+ "le dépôt courant (409 WAREHOUSE_MISMATCH sinon) ; la destination doit être active et accessible "
					+ "à l'utilisateur.")
	TransferStockResponse transfer(@Valid @RequestBody TransferStockRequest request,
			@CurrentWarehouse WarehouseContext warehouse) {
		this.warehouseAccess.requireCurrent(request.fromWarehouseId(), warehouse.id());
		this.warehouseAccess.requireAccessible(request.fromWarehouseId());
		this.warehouseAccess.requireAccessible(request.toWarehouseId());
		List<StockMovement> movements = this.stock.transfer(request.fromWarehouseId(), request.toWarehouseId(),
				request.productId(), request.quantity(), request.note());
		List<StockMovementResponse> responses = this.queries.toResponses(movements);
		return new TransferStockResponse(true, movements.get(0).getCorrelationId(), responses.get(0), responses.get(1));
	}

}
