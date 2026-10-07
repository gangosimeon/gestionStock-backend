package com.geststock.stock;

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

import com.geststock.common.api.PageQuery;
import com.geststock.common.api.PageResponse;
import com.geststock.common.security.Permissions;
import com.geststock.common.security.RequiresPermission;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.ForbiddenOperationException;
import com.geststock.exception.NotFoundException;
import com.geststock.stock.dto.CreateStockMovementRequest;
import com.geststock.stock.dto.ReverseMovementRequest;
import com.geststock.stock.dto.StockLevelResponse;
import com.geststock.stock.dto.StockMovementFilter;
import com.geststock.stock.dto.StockMovementResponse;
import com.geststock.warehouse.CurrentWarehouse;
import com.geststock.warehouse.WarehouseAccess;
import com.geststock.warehouse.WarehouseContext;

/**
 * Stock : historique, niveaux (lecture seule) et mouvements manuels. Aucun endpoint n'écrit
 * un niveau : seule {@link StockService} le fait, à partir d'un mouvement.
 */
@RestController
@RequestMapping("/api/stock")
@Tag(name = "Stock")
class StockController {

	private final StockService stock;

	private final StockQueryService queries;

	private final WarehouseAccess warehouseAccess;

	StockController(StockService stock, StockQueryService queries, WarehouseAccess warehouseAccess) {
		this.stock = stock;
		this.queries = queries;
		this.warehouseAccess = warehouseAccess;
	}

	@GetMapping("/movements")
	@RequiresPermission(Permissions.STOCK_READ)
	@Operation(summary = "Historique des mouvements",
			description = "Mouvements du dépôt courant, du plus récent au plus ancien. Sans page ni size : liste "
					+ "complète (compatibilité front, plafond 5 000). allWarehouses=true exige warehouse:all.")
	ResponseEntity<PageResponse<StockMovementResponse>> movements(@ParameterObject StockMovementFilter filter,
			@CurrentWarehouse WarehouseContext warehouse, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size,
			@Parameter(description = "createdAt | quantity | type, asc | desc") @RequestParam(
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
		return PageResponse.toResponse(this.queries.searchMovements(filter, scope, query), query);
	}

	@GetMapping("/movements/{id}")
	@RequiresPermission(Permissions.STOCK_READ)
	@Operation(summary = "Consulter un mouvement")
	StockMovementResponse movement(@PathVariable UUID id) {
		return this.queries.toResponse(visibleMovement(id));
	}

	@PostMapping("/movements")
	@RequiresPermission(Permissions.STOCK_ADJUST)
	@Operation(summary = "Saisir un mouvement manuel",
			description = "Dans le dépôt courant, au nom de l'utilisateur connecté. Refusé si le stock deviendrait "
					+ "négatif (422 STOCK_INSUFFICIENT).")
	ResponseEntity<StockMovementResponse> create(@Valid @RequestBody CreateStockMovementRequest request,
			@CurrentWarehouse WarehouseContext warehouse) {
		StockMovementType type = switch (request.reason()) {
			case SUPPLY -> StockMovementType.RECEIPT;
			case SALE -> StockMovementType.SALE;
			case LOSS -> StockMovementType.LOSS;
			case ADJUSTMENT -> StockMovementType.ADJUSTMENT;
		};
		StockMovementCommand command = StockMovementCommand
			.of(warehouse.id(), request.productId(), type, request.quantity(), StockSourceType.MANUAL,
					request.note() != null && !request.note().isBlank() ? request.note().trim() : null)
			.withUnitCost(request.unitCost());
		StockMovementResponse created = this.queries.toResponse(this.stock.applyMovement(command));
		return ResponseEntity.created(URI.create("/api/stock/movements/" + created.id())).body(created);
	}

	@PostMapping("/movements/{id}/reversal")
	@RequiresPermission(Permissions.STOCK_ADJUST)
	@Operation(summary = "Annuler un mouvement manuel",
			description = "Écrit un mouvement REVERSAL de quantité opposée ; le mouvement d'origine reste dans "
					+ "l'historique. Une seule annulation par mouvement.")
	ResponseEntity<StockMovementResponse> reverse(@PathVariable UUID id,
			@Valid @RequestBody(required = false) ReverseMovementRequest request) {
		StockMovement original = visibleMovement(id);
		this.warehouseAccess.requireAccessible(original.getWarehouseId());
		StockMovementResponse created = this.queries
			.toResponse(this.stock.reverse(id, request != null ? request.reason() : null));
		return ResponseEntity.created(URI.create("/api/stock/movements/" + created.id())).body(created);
	}

	@GetMapping("/levels")
	@RequiresPermission(Permissions.STOCK_READ)
	@Operation(summary = "Niveaux de stock du dépôt courant", description = "Lecture seule.")
	ResponseEntity<PageResponse<StockLevelResponse>> levels(@CurrentWarehouse WarehouseContext warehouse,
			@RequestParam(required = false) UUID productId, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size,
			@Parameter(description = "quantity | updatedAt, asc | desc") @RequestParam(required = false) List<String> sort) {
		PageQuery query = new PageQuery(page, size, sort);
		return PageResponse.toResponse(this.queries.searchLevels(warehouse.id(), productId, query), query);
	}

	/** Mouvement d'un dépôt visible par l'utilisateur ; sinon 404 (existence non révélée). */
	private StockMovement visibleMovement(UUID id) {
		StockMovement movement = this.queries.getMovement(id);
		if (!this.warehouseAccess.canSee(movement.getWarehouseId())) {
			throw new NotFoundException(ErrorCode.STOCK_MOVEMENT_NOT_FOUND,
					ErrorCode.STOCK_MOVEMENT_NOT_FOUND.defaultMessage());
		}
		return movement;
	}

}
