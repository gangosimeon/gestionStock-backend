package com.geststock.expense;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.audit.Audited;
import com.geststock.common.api.PageQuery;
import com.geststock.common.api.PageResponse;
import com.geststock.common.security.Permissions;
import com.geststock.common.security.RequiresPermission;
import com.geststock.exception.ApiError;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.ForbiddenOperationException;
import com.geststock.expense.dto.CancelExpenseRequest;
import com.geststock.expense.dto.ExpenseFilter;
import com.geststock.expense.dto.ExpenseRequest;
import com.geststock.expense.dto.ExpenseResponse;
import com.geststock.expense.dto.ExpenseSummaryResponse;
import com.geststock.warehouse.CurrentWarehouse;
import com.geststock.warehouse.WarehouseAccess;
import com.geststock.warehouse.WarehouseContext;

/**
 * Dépenses du dépôt courant (contrat front {@code /api/expenses}). Pas de modification ni de
 * suppression : une dépense erronée s'annule, avec un motif.
 */
@RestController
@RequestMapping("/api/expenses")
@Tag(name = "Dépenses")
class ExpenseController {

	private final ExpenseService expenses;

	private final WarehouseAccess warehouseAccess;

	ExpenseController(ExpenseService expenses, WarehouseAccess warehouseAccess) {
		this.expenses = expenses;
		this.warehouseAccess = warehouseAccess;
	}

	@GetMapping
	@RequiresPermission(Permissions.EXPENSE_READ)
	@Operation(summary = "Rechercher les dépenses",
			description = "Du dépôt courant (allWarehouses=true exige warehouse:all), les plus récentes d'abord. "
					+ "Filtres dateFrom, dateTo (AAAA-MM-JJ inclus), category, paymentMethod, status, search.")
	ResponseEntity<PageResponse<ExpenseResponse>> list(@ParameterObject ExpenseFilter filter,
			@CurrentWarehouse WarehouseContext warehouse, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size,
			@Parameter(description = "expenseDate | createdAt | amount | number | category, asc | desc") @RequestParam(
					required = false) List<String> sort) {
		PageQuery query = new PageQuery(page, size, sort);
		return PageResponse.toResponse(this.expenses.list(filter, scope(filter, warehouse), query), query);
	}

	@GetMapping("/summary")
	@RequiresPermission(Permissions.EXPENSE_READ)
	@Operation(summary = "Totaux des dépenses",
			description = "Total, nombre, part en espèces et répartition par catégorie des dépenses payées, sur les "
					+ "mêmes critères que la recherche ; les dépenses annulées sont seulement comptées à part.")
	ExpenseSummaryResponse summary(@ParameterObject ExpenseFilter filter, @CurrentWarehouse WarehouseContext warehouse) {
		return this.expenses.summary(filter, scope(filter, warehouse));
	}

	@GetMapping("/{id}")
	@RequiresPermission(Permissions.EXPENSE_READ)
	@Operation(summary = "Consulter une dépense")
	ExpenseResponse get(@PathVariable UUID id) {
		return this.expenses.get(id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Audited(action = "CREATE", entityType = "EXPENSE", idParam = "")
	@RequiresPermission(Permissions.EXPENSE_CREATE)
	@Operation(summary = "Enregistrer une dépense",
			description = "Dans le dépôt courant. En espèces (défaut), elle sort de ma caisse ouverte dans ce dépôt ; "
					+ "mobile money, virement et chèque ne passent pas par la caisse.")
	@ApiResponse(responseCode = "201", description = "Dépense enregistrée")
	@ApiResponse(responseCode = "409", description = "NO_OPEN_CASH_SESSION ou CASH_SESSION_OTHER_WAREHOUSE (espèces)",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@ApiResponse(responseCode = "422", description = "CASH_INSUFFICIENT",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	ResponseEntity<ExpenseResponse> create(@Valid @RequestBody ExpenseRequest request,
			@CurrentWarehouse WarehouseContext warehouse) {
		ExpenseResponse created = this.expenses.create(request, warehouse.id());
		return ResponseEntity.created(URI.create("/api/expenses/" + created.id())).body(created);
	}

	@PostMapping("/{id}/cancel")
	@Audited(action = "CANCEL", entityType = "EXPENSE", idParam = "id")
	@RequiresPermission(Permissions.EXPENSE_DELETE)
	@Operation(summary = "Annuler une dépense",
			description = "Motif obligatoire ; la dépense reste visible et sort des totaux. En espèces, l'argent revient "
					+ "dans ma caisse ouverte du dépôt de la dépense. Depuis le dépôt de la dépense.")
	@ApiResponse(responseCode = "200", description = "Dépense annulée")
	@ApiResponse(responseCode = "409",
			description = "EXPENSE_ALREADY_CANCELLED, NO_OPEN_CASH_SESSION, CASH_SESSION_OTHER_WAREHOUSE ou "
					+ "WAREHOUSE_MISMATCH",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	ExpenseResponse cancel(@PathVariable UUID id, @Valid @RequestBody CancelExpenseRequest request,
			@CurrentWarehouse WarehouseContext warehouse) {
		return this.expenses.cancel(id, request.reason(), warehouse.id());
	}

	/** Dépôt courant, ou tous les dépôts ({@code null}) avec {@code warehouse:all}. */
	private UUID scope(ExpenseFilter filter, WarehouseContext warehouse) {
		if (!Boolean.TRUE.equals(filter.allWarehouses())) {
			return warehouse.id();
		}
		if (!this.warehouseAccess.seesAllWarehouses()) {
			throw new ForbiddenOperationException(ErrorCode.WAREHOUSE_ACCESS_DENIED,
					ErrorCode.WAREHOUSE_ACCESS_DENIED.defaultMessage());
		}
		return null;
	}

}
