package com.geststock.purchase;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.audit.Audited;
import com.geststock.common.api.PageQuery;
import com.geststock.common.api.PageResponse;
import com.geststock.common.security.Permissions;
import com.geststock.common.security.RequiresPermission;
import com.geststock.exception.ApiError;
import com.geststock.purchase.dto.PurchaseOrderPaymentRequest;
import com.geststock.purchase.dto.PurchaseOrderResponse;
import com.geststock.purchase.dto.SupplierAccountResponse;
import com.geststock.purchase.dto.SupplierPaymentRequest;
import com.geststock.purchase.dto.SupplierPaymentResponse;
import com.geststock.warehouse.CurrentWarehouse;
import com.geststock.warehouse.WarehouseContext;

/**
 * Paiements fournisseurs (contrats front {@code /api/suppliers/{id}/payments} et
 * {@code /api/purchases/orders/{id}/pay}, une seule règle). Servi par le module achats : le
 * module fournisseur ne dépend pas des achats.
 */
@RestController
class SupplierPaymentController {

	private final SupplierPaymentService payments;

	SupplierPaymentController(SupplierPaymentService payments) {
		this.payments = payments;
	}

	@GetMapping("/api/suppliers/{supplierId}/payments")
	@Tag(name = "Fournisseurs")
	@RequiresPermission(Permissions.PURCHASE_READ)
	@Operation(summary = "Paiements d'un fournisseur", description = "Du plus récent au plus ancien, avec leur "
			+ "répartition sur les commandes. Ceux des dépôts de l'utilisateur ; tous avec warehouse:all.")
	ResponseEntity<PageResponse<SupplierPaymentResponse>> list(@PathVariable UUID supplierId,
			@Parameter(description = "Recherche texte : n° de paiement, référence externe, note") @RequestParam(required = false) String q,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
			@Parameter(description = "paymentDate | createdAt | amount | number, asc | desc") @RequestParam(
					required = false) List<String> sort) {
		PageQuery query = new PageQuery(page, size, sort);
		return PageResponse.toResponse(this.payments.list(supplierId, q, query), query);
	}

	@PostMapping("/api/suppliers/{supplierId}/payments")
	@ResponseStatus(HttpStatus.CREATED)
	@Tag(name = "Fournisseurs")
	@Audited(action = "PAY", entityType = "SUPPLIER_PAYMENT", idParam = "")
	@RequiresPermission(Permissions.PURCHASE_PAY)
	@Operation(summary = "Payer un fournisseur",
			description = "Paiement du dépôt courant : imputé sur la commande indiquée (de ce dépôt, sinon 409 "
					+ "WAREHOUSE_MISMATCH), sinon sur les plus anciennes du dépôt ayant un reste à payer. Refusé au-delà "
					+ "du reste dû dans le dépôt (pas de trop-payé). En espèces, sort de ma caisse ouverte ; "
					+ "virement et mobile money n'y passent pas. En espèces, la caisse doit être celle du dépôt courant "
					+ "(409 CASH_SESSION_OTHER_WAREHOUSE).")
	@ApiResponse(responseCode = "201", description = "Paiement enregistré")
	@ApiResponse(responseCode = "422",
			description = "SUPPLIER_PAYMENT_EXCEEDS_BALANCE, PURCHASE_ORDER_PAYMENT_EXCEEDS_REMAINING ou CASH_INSUFFICIENT",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	ResponseEntity<SupplierPaymentResponse> pay(@PathVariable UUID supplierId,
			@Valid @RequestBody SupplierPaymentRequest request, @CurrentWarehouse WarehouseContext warehouse) {
		SupplierPaymentResponse created = this.payments.pay(supplierId, request, warehouse.id());
		return ResponseEntity.created(URI.create("/api/suppliers/" + supplierId + "/payments")).body(created);
	}

	@GetMapping("/api/suppliers/{supplierId}/account")
	@Tag(name = "Fournisseurs")
	@RequiresPermission(Permissions.PURCHASE_READ)
	@Operation(summary = "Compte d'un fournisseur",
			description = "Commandé, reçu, dû, payé et reste à payer, calculés par le serveur sur les commandes des "
					+ "dépôts visibles ; currentWarehouseRemainingToPay : reste à payer dans le dépôt courant.")
	SupplierAccountResponse account(@PathVariable UUID supplierId, @CurrentWarehouse WarehouseContext warehouse) {
		return this.payments.account(supplierId, warehouse.id());
	}

	@PostMapping("/api/purchases/orders/{id}/pay")
	@Tag(name = "Achats")
	@Audited(action = "PAY", entityType = "PURCHASE_ORDER", idParam = "id")
	@RequiresPermission(Permissions.PURCHASE_PAY)
	@Operation(summary = "Payer une commande", description = "Même règle que le paiement d'un fournisseur, imputé "
			+ "sur cette commande ; renvoie la commande avec son montant payé à jour.")
	@ApiResponse(responseCode = "200", description = "Commande à jour")
	@ApiResponse(responseCode = "422", description = "PURCHASE_ORDER_PAYMENT_EXCEEDS_REMAINING ou CASH_INSUFFICIENT",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	PurchaseOrderResponse payOrder(@PathVariable UUID id, @Valid @RequestBody PurchaseOrderPaymentRequest request,
			@CurrentWarehouse WarehouseContext warehouse) {
		return this.payments.payOrder(id, request, warehouse.id());
	}

}
