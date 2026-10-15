package com.geststock.cash;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.audit.Audited;
import com.geststock.cash.dto.CustomerPaymentRequest;
import com.geststock.cash.dto.CustomerPaymentResponse;
import com.geststock.common.api.PageQuery;
import com.geststock.common.api.PageResponse;
import com.geststock.common.security.Permissions;
import com.geststock.common.security.RequiresPermission;
import com.geststock.warehouse.CurrentWarehouse;
import com.geststock.warehouse.WarehouseContext;

/**
 * Règlements clients (contrat front {@code /api/customers/{id}/payments}). Servi par le module
 * caisse : un règlement est un encaissement.
 */
@RestController
@RequestMapping("/api/customers/{customerId}/payments")
@Tag(name = "Clients")
class CustomerPaymentController {

	private final CustomerPaymentService payments;

	CustomerPaymentController(CustomerPaymentService payments) {
		this.payments = payments;
	}

	@GetMapping
	@RequiresPermission(Permissions.CUSTOMER_READ)
	@Operation(summary = "Règlements d'un client")
	ResponseEntity<PageResponse<CustomerPaymentResponse>> list(@PathVariable UUID customerId,
			@Parameter(description = "Recherche texte : référence, note") @RequestParam(required = false) String q,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
			@Parameter(description = "createdAt | amount, asc | desc") @RequestParam(required = false) List<String> sort) {
		PageQuery query = new PageQuery(page, size, sort);
		return PageResponse.toResponse(this.payments.list(customerId, q, query), query);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Audited(action = "PAY", entityType = "CUSTOMER", idParam = "customerId")
	@RequiresPermission(Permissions.CUSTOMER_PAY)
	@Operation(summary = "Enregistrer un règlement",
			description = "Encaissé dans ma caisse ouverte du dépôt courant (409 CASH_SESSION_OTHER_WAREHOUSE si elle est "
					+ "ailleurs) ; refusé s'il dépasse la dette du client (pas de trop-perçu).")
	ResponseEntity<CustomerPaymentResponse> pay(@PathVariable UUID customerId,
			@Valid @RequestBody CustomerPaymentRequest request, @CurrentWarehouse WarehouseContext warehouse) {
		CustomerPaymentResponse created = this.payments.pay(customerId, request, warehouse.id());
		return ResponseEntity.created(URI.create("/api/customers/" + customerId + "/payments")).body(created);
	}

}
