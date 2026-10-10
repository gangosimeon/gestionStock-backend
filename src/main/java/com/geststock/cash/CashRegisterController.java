package com.geststock.cash;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springdoc.core.annotations.ParameterObject;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.node.NullNode;

import com.geststock.cash.dto.CashCorrectionRequest;
import com.geststock.cash.dto.CashEntryResponse;
import com.geststock.cash.dto.CashOperationRequest;
import com.geststock.cash.dto.CashSessionFilter;
import com.geststock.cash.dto.CashSessionResponse;
import com.geststock.cash.dto.CloseCashSessionRequest;
import com.geststock.cash.dto.OpenCashSessionRequest;
import com.geststock.common.api.PageQuery;
import com.geststock.common.api.PageResponse;
import com.geststock.common.security.Permissions;
import com.geststock.common.security.RequiresPermission;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.ForbiddenOperationException;
import com.geststock.warehouse.CurrentWarehouse;
import com.geststock.warehouse.WarehouseAccess;
import com.geststock.warehouse.WarehouseContext;

/**
 * Caisse. Chemins et formats du contrat du front ({@code /api/cash-register/*}), mais la
 * caisse n'est plus globale : chaque endpoint agit sur la caisse de l'utilisateur connecté.
 */
@RestController
@RequestMapping("/api/cash-register")
@Tag(name = "Caisse")
class CashRegisterController {

	private static final String OPERATE_OR_READ = "hasAnyAuthority('" + Permissions.CASH_OPERATE + "', '"
			+ Permissions.CASH_READ + "')";

	private final CashService cash;

	private final WarehouseAccess warehouseAccess;

	CashRegisterController(CashService cash, WarehouseAccess warehouseAccess) {
		this.cash = cash;
		this.warehouseAccess = warehouseAccess;
	}

	@GetMapping("/current")
	@RequiresPermission(Permissions.CASH_OPERATE)
	@Operation(summary = "Ma caisse ouverte", description = "Corps null si l'utilisateur n'a pas de caisse ouverte.")
	ResponseEntity<Object> current() {
		return ResponseEntity.ok(this.cash.current().<Object>map(session -> session).orElse(NullNode.getInstance()));
	}

	@GetMapping("/sessions")
	@PreAuthorize(OPERATE_OR_READ)
	@Operation(summary = "Historique des caisses",
			description = "Ses propres caisses ; toutes celles du dépôt courant avec cash:read (filtre cashierId). "
					+ "Plus récentes d'abord. allWarehouses=true exige warehouse:all.")
	ResponseEntity<PageResponse<CashSessionResponse>> sessions(@ParameterObject CashSessionFilter filter,
			@CurrentWarehouse WarehouseContext warehouse, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size,
			@Parameter(description = "openedAt | closedAt | number, asc | desc") @RequestParam(
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
		return PageResponse.toResponse(this.cash.history(filter, scope, query), query);
	}

	@GetMapping("/sessions/{id}")
	@PreAuthorize(OPERATE_OR_READ)
	@Operation(summary = "Consulter une caisse", description = "La sienne, ou toute caisse visible avec cash:read.")
	CashSessionResponse session(@PathVariable UUID id) {
		return this.cash.get(id);
	}

	@GetMapping("/sessions/{id}/operations")
	@PreAuthorize(OPERATE_OR_READ)
	@Operation(summary = "Écritures d'une caisse", description = "Encaissements et décaissements, plus récents d'abord.")
	ResponseEntity<PageResponse<CashEntryResponse>> operations(@PathVariable UUID id,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
			@Parameter(description = "createdAt | amount, asc | desc") @RequestParam(required = false) List<String> sort) {
		PageQuery query = new PageQuery(page, size, sort);
		return PageResponse.toResponse(this.cash.entries(id, query), query);
	}

	@PostMapping("/open")
	@RequiresPermission(Permissions.CASH_OPERATE)
	@Operation(summary = "Ouvrir ma caisse", description = "Dans le dépôt courant ; une seule caisse ouverte par utilisateur.")
	ResponseEntity<CashSessionResponse> open(@Valid @RequestBody OpenCashSessionRequest request,
			@CurrentWarehouse WarehouseContext warehouse) {
		CashSessionResponse opened = this.cash.open(request, warehouse.id());
		return ResponseEntity.created(URI.create("/api/cash-register/sessions/" + opened.id())).body(opened);
	}

	@PostMapping("/operations")
	@RequiresPermission(Permissions.CASH_OPERATE)
	@Operation(summary = "Apport ou sortie d'espèces",
			description = "Dans ma caisse ouverte ; une sortie ne peut dépasser les espèces attendues.")
	ResponseEntity<CashEntryResponse> operation(@Valid @RequestBody CashOperationRequest request) {
		CashEntryResponse created = this.cash.recordManual(request);
		return ResponseEntity.created(URI.create("/api/cash-register/sessions/" + created.sessionId() + "/operations"))
			.body(created);
	}

	@PostMapping("/operations/{id}/reversal")
	@RequiresPermission(Permissions.CASH_OPERATE)
	@Operation(summary = "Corriger un apport ou une sortie",
			description = "Écriture inverse du même montant dans ma caisse ouverte ; l'opération d'origine reste. "
					+ "Une seule correction par opération ; une vente se corrige par annulation ou retour.")
	ResponseEntity<CashEntryResponse> reverse(@PathVariable UUID id, @Valid @RequestBody CashCorrectionRequest request) {
		CashEntryResponse created = this.cash.reverseManual(id, request.reason());
		return ResponseEntity.created(URI.create("/api/cash-register/sessions/" + created.sessionId() + "/operations"))
			.body(created);
	}

	@PostMapping("/close")
	@RequiresPermission(Permissions.CASH_OPERATE)
	@Operation(summary = "Clôturer ma caisse", description = "Espèces comptées ; attendu et écart figés.")
	CashSessionResponse close(@Valid @RequestBody CloseCashSessionRequest request) {
		return this.cash.close(request);
	}

}
