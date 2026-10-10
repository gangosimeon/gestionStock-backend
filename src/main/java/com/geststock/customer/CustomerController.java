package com.geststock.customer;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springdoc.core.annotations.ParameterObject;

import org.springframework.http.ResponseEntity;
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
import com.geststock.common.api.OkResponse;
import com.geststock.common.api.PageQuery;
import com.geststock.common.api.PageResponse;
import com.geststock.common.api.SetActiveRequest;
import com.geststock.common.security.Permissions;
import com.geststock.common.security.RequiresPermission;
import com.geststock.customer.dto.CustomerAccountEntryResponse;
import com.geststock.customer.dto.CustomerAccountResponse;
import com.geststock.customer.dto.CustomerFilter;
import com.geststock.customer.dto.CustomerRequest;
import com.geststock.customer.dto.CustomerResponse;

/**
 * Clients. Chemins, verbes et formats du contrat du front ({@code /api/customers}, mise à jour
 * en {@code PATCH}). Le compte client est en lecture seule ici : il est alimenté par les
 * ventes, les paiements et les retours. {@code /api/customers/{id}/sales|payments} seront
 * servis par ces modules.
 */
@RestController
@RequestMapping("/api/customers")
@Tag(name = "Clients")
class CustomerController {

	private final CustomerService customers;

	private final CustomerAccountService accounts;

	CustomerController(CustomerService customers, CustomerAccountService accounts) {
		this.customers = customers;
		this.accounts = accounts;
	}

	@GetMapping
	@RequiresPermission(Permissions.CUSTOMER_READ)
	@Operation(summary = "Rechercher des clients",
			description = "Actifs par défaut. q cherche dans le code, le nom, l'email et le téléphone (chiffres seuls "
					+ "acceptés). Sans page ni size : liste complète (compatibilité front, plafond 5 000).")
	ResponseEntity<PageResponse<CustomerResponse>> list(@ParameterObject CustomerFilter filter,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
			@Parameter(description = "name | code | creditLimit | createdAt, asc | desc") @RequestParam(
					required = false) List<String> sort) {
		PageQuery query = new PageQuery(page, size, sort);
		return PageResponse.toResponse(this.customers.search(filter, query), query);
	}

	@GetMapping("/{id}")
	@RequiresPermission(Permissions.CUSTOMER_READ)
	@Operation(summary = "Consulter un client", description = "Avec son solde dû et son crédit disponible.")
	CustomerResponse get(@PathVariable UUID id) {
		return this.customers.get(id);
	}

	@PostMapping
	@Audited(action = "CREATE", entityType = "CUSTOMER", idParam = "")
	@RequiresPermission(Permissions.CUSTOMER_CREATE)
	@Operation(summary = "Créer un client",
			description = "Code généré (CLI-000001) s'il est absent. Un plafond de crédit > 0 exige customer:credit.")
	ResponseEntity<CustomerResponse> create(@Valid @RequestBody CustomerRequest request) {
		CustomerResponse created = this.customers.create(request);
		return ResponseEntity.created(URI.create("/api/customers/" + created.id())).body(created);
	}

	@PatchMapping("/{id}")
	@Audited(action = "UPDATE", entityType = "CUSTOMER")
	@RequiresPermission(Permissions.CUSTOMER_UPDATE)
	@Operation(summary = "Modifier un client (verbe du front)",
			description = "name, phone et email remplacés ; creditLimit et champs étendus conservés s'ils sont absents. "
					+ "Changer le plafond de crédit exige customer:credit.")
	CustomerResponse patch(@PathVariable UUID id, @Valid @RequestBody CustomerRequest request) {
		return this.customers.update(id, request);
	}

	@PutMapping("/{id}")
	@Audited(action = "UPDATE", entityType = "CUSTOMER")
	@RequiresPermission(Permissions.CUSTOMER_UPDATE)
	@Operation(summary = "Modifier un client", description = "Mêmes règles que PATCH.")
	CustomerResponse update(@PathVariable UUID id, @Valid @RequestBody CustomerRequest request) {
		return this.customers.update(id, request);
	}

	@PatchMapping("/{id}/active")
	@Audited(action = "SET_ACTIVE", entityType = "CUSTOMER")
	@RequiresPermission(Permissions.CUSTOMER_DELETE)
	@Operation(summary = "Activer ou désactiver un client")
	CustomerResponse setActive(@PathVariable UUID id, @Valid @RequestBody SetActiveRequest request) {
		return this.customers.setActive(id, request.active());
	}

	@DeleteMapping("/{id}")
	@Audited(action = "DEACTIVATE", entityType = "CUSTOMER")
	@RequiresPermission(Permissions.CUSTOMER_DELETE)
	@Operation(summary = "Supprimer un client (désactivation)",
			description = "Le client est désactivé, jamais effacé : ventes, paiements et dette le référencent.")
	OkResponse delete(@PathVariable UUID id) {
		this.customers.setActive(id, false);
		return OkResponse.success();
	}

	@GetMapping("/{id}/account")
	@RequiresPermission(Permissions.CUSTOMER_READ)
	@Operation(summary = "Situation de crédit", description = "Plafond, solde dû et crédit disponible.")
	CustomerAccountResponse account(@PathVariable UUID id) {
		return this.accounts.creditStatus(id);
	}

	@GetMapping("/{id}/account/entries")
	@RequiresPermission(Permissions.CUSTOMER_READ)
	@Operation(summary = "Relevé du compte client",
			description = "Ventes à crédit, règlements, avoirs et annulations, du plus récent au plus ancien.")
	ResponseEntity<PageResponse<CustomerAccountEntryResponse>> entries(@PathVariable UUID id,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
			@Parameter(description = "createdAt | amount, asc | desc") @RequestParam(required = false) List<String> sort) {
		PageQuery query = new PageQuery(page, size, sort);
		return PageResponse.toResponse(this.accounts.entries(id, query), query);
	}

}
