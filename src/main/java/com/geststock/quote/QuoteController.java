package com.geststock.quote;

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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
import com.geststock.exception.ErrorCode;
import com.geststock.exception.ForbiddenOperationException;
import com.geststock.quote.dto.QuoteActionRequests;
import com.geststock.quote.dto.QuoteFilter;
import com.geststock.quote.dto.QuoteReference;
import com.geststock.quote.dto.QuoteRequest;
import com.geststock.quote.dto.QuoteResponse;
import com.geststock.warehouse.CurrentWarehouse;
import com.geststock.warehouse.WarehouseAccess;
import com.geststock.warehouse.WarehouseContext;

/**
 * Devis : propositions commerciales, sans effet sur le stock, la caisse ni le compte client.
 * Consultation {@code quote:read}, gestion {@code quote:manage}. Dépôt courant ; un devis d'un
 * dépôt hors périmètre est introuvable (404).
 */
@RestController
@RequestMapping("/api/quotes")
@Tag(name = "Devis")
class QuoteController {

	private final QuoteService quotes;

	private final WarehouseAccess warehouseAccess;

	QuoteController(QuoteService quotes, WarehouseAccess warehouseAccess) {
		this.quotes = quotes;
		this.warehouseAccess = warehouseAccess;
	}

	@GetMapping
	@RequiresPermission(Permissions.QUOTE_READ)
	@Operation(summary = "Rechercher des devis",
			description = "Devis du dépôt courant, des plus récents aux plus anciens ; filtres q (numéro, client, objet), "
					+ "status (dont EXPIRED : émis et validité dépassée), customerId, from, to (date du devis). "
					+ "allWarehouses=true exige warehouse:all.")
	ResponseEntity<PageResponse<QuoteResponse>> list(@ParameterObject QuoteFilter filter,
			@CurrentWarehouse WarehouseContext warehouse, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size,
			@Parameter(description = "quoteDate | number | total | customerName, asc | desc") @RequestParam(
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
		return PageResponse.toResponse(this.quotes.search(filter, scope, query), query);
	}

	@GetMapping("/{id}")
	@RequiresPermission(Permissions.QUOTE_READ)
	@Operation(summary = "Consulter un devis")
	QuoteResponse get(@PathVariable UUID id) {
		return this.quotes.get(id);
	}

	@GetMapping("/for-sale/{saleId}")
	@PreAuthorize("hasAnyAuthority('quote:read', 'sale:read', 'sale:create')")
	@Operation(summary = "Devis facturé par une vente",
			description = "Informations du devis reprises sur la facture de la vente (objet, lot, références…). "
					+ "Corps vide si la vente n'est issue d'aucun devis.")
	QuoteReference forSale(@PathVariable UUID saleId) {
		return this.quotes.forSale(saleId).orElse(null);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Audited(action = "CREATE", entityType = "QUOTE", idParam = "")
	@RequiresPermission(Permissions.QUOTE_MANAGE)
	@Operation(summary = "Créer un devis",
			description = "Brouillon dans le dépôt courant. Client et produits existants ; total calculé par le serveur "
					+ "(quantité × prix unitaire retenu). Aucun mouvement de stock.")
	ResponseEntity<QuoteResponse> create(@Valid @RequestBody QuoteRequest request,
			@CurrentWarehouse WarehouseContext warehouse) {
		QuoteResponse created = this.quotes.create(request, warehouse.id());
		return ResponseEntity.created(URI.create("/api/quotes/" + created.id())).body(created);
	}

	@PutMapping("/{id}")
	@Audited(action = "UPDATE", entityType = "QUOTE")
	@RequiresPermission(Permissions.QUOTE_MANAGE)
	@Operation(summary = "Modifier un devis", description = "Brouillon ou émis seulement (409 QUOTE_NOT_EDITABLE sinon).")
	QuoteResponse update(@PathVariable UUID id, @Valid @RequestBody QuoteRequest request) {
		return this.quotes.update(id, request);
	}

	@PostMapping("/{id}/issue")
	@Audited(action = "ISSUE", entityType = "QUOTE")
	@RequiresPermission(Permissions.QUOTE_MANAGE)
	@Operation(summary = "Émettre un devis", description = "Brouillon → émis (remis au client).")
	QuoteResponse issue(@PathVariable UUID id) {
		return this.quotes.issue(id);
	}

	@PostMapping("/{id}/accept")
	@Audited(action = "ACCEPT", entityType = "QUOTE")
	@RequiresPermission(Permissions.QUOTE_MANAGE)
	@Operation(summary = "Marquer un devis comme accepté", description = "Émis → accepté.")
	QuoteResponse accept(@PathVariable UUID id) {
		return this.quotes.accept(id);
	}

	@PostMapping("/{id}/refuse")
	@Audited(action = "REFUSE", entityType = "QUOTE")
	@RequiresPermission(Permissions.QUOTE_MANAGE)
	@Operation(summary = "Marquer un devis comme refusé", description = "Émis → refusé.")
	QuoteResponse refuse(@PathVariable UUID id) {
		return this.quotes.refuse(id);
	}

	@PostMapping("/{id}/cancel")
	@Audited(action = "CANCEL", entityType = "QUOTE")
	@RequiresPermission(Permissions.QUOTE_MANAGE)
	@Operation(summary = "Annuler un devis", description = "Brouillon, émis ou accepté ; motif facultatif.")
	QuoteResponse cancel(@PathVariable UUID id, @Valid @RequestBody(required = false) QuoteActionRequests.Cancel request) {
		return this.quotes.cancel(id, request != null ? request.reason() : null);
	}

	@PostMapping("/{id}/duplicate")
	@ResponseStatus(HttpStatus.CREATED)
	@Audited(action = "CREATE", entityType = "QUOTE")
	@RequiresPermission(Permissions.QUOTE_MANAGE)
	@Operation(summary = "Dupliquer un devis", description = "Nouveau brouillon daté d'aujourd'hui, dans le dépôt courant.")
	ResponseEntity<QuoteResponse> duplicate(@PathVariable UUID id, @CurrentWarehouse WarehouseContext warehouse) {
		QuoteResponse created = this.quotes.duplicate(id, warehouse.id());
		return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/api/quotes/" + created.id())).body(created);
	}

	@PostMapping("/{id}/invoice")
	@Audited(action = "INVOICE", entityType = "QUOTE")
	@RequiresPermission(Permissions.QUOTE_MANAGE)
	@Operation(summary = "Rattacher la facture (vente) d'un devis",
			description = "La facture d'un devis est la vente enregistrée à la caisse à partir de lui (stock, paiement et "
					+ "crédit selon les règles des ventes). Vérifie même client, même dépôt, vente postérieure au devis et "
					+ "non annulée ; une seule fois par devis et par vente (répéter la même demande est sans effet). "
					+ "Aucune écriture de stock ni de caisse ici.")
	QuoteResponse invoice(@PathVariable UUID id, @Valid @RequestBody QuoteActionRequests.Invoice request) {
		return this.quotes.invoice(id, request.saleId());
	}

}
