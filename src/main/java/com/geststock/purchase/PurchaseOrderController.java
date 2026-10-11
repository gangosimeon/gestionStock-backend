package com.geststock.purchase;

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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.audit.Audited;
import com.geststock.common.api.PageQuery;
import com.geststock.common.api.PageResponse;
import com.geststock.common.security.Permissions;
import com.geststock.common.security.RequiresPermission;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.ForbiddenOperationException;
import com.geststock.purchase.dto.ClosePurchaseOrderRequest;
import com.geststock.purchase.dto.CreatePurchaseOrderRequest;
import com.geststock.purchase.dto.GoodsReceiptResponse;
import com.geststock.purchase.dto.PurchaseOrderFilter;
import com.geststock.purchase.dto.PurchaseOrderResponse;
import com.geststock.purchase.dto.ReceivePurchaseOrderRequest;
import com.geststock.purchase.dto.SupplierInvoiceRequest;
import com.geststock.purchase.dto.SupplierInvoiceResponse;
import com.geststock.warehouse.CurrentWarehouse;
import com.geststock.warehouse.WarehouseAccess;
import com.geststock.warehouse.WarehouseContext;

/**
 * Commandes fournisseurs et réceptions. Chemins du contrat du front
 * ({@code /api/purchases/orders}, {@code /receive}). Facture et paiements
 * ({@code /invoice}, {@code /pay}) relèvent d'une étape ultérieure.
 */
@RestController
@RequestMapping("/api/purchases/orders")
@Tag(name = "Achats")
class PurchaseOrderController {

	private final PurchaseOrderService orders;

	private final GoodsReceiptService receipts;

	private final WarehouseAccess warehouseAccess;

	PurchaseOrderController(PurchaseOrderService orders, GoodsReceiptService receipts, WarehouseAccess warehouseAccess) {
		this.orders = orders;
		this.receipts = receipts;
		this.warehouseAccess = warehouseAccess;
	}

	@GetMapping
	@RequiresPermission(Permissions.PURCHASE_READ)
	@Operation(summary = "Lister les commandes fournisseurs",
			description = "Commandes du dépôt courant, des plus récentes aux plus anciennes. Sans page ni size : liste "
					+ "complète (compatibilité front, plafond 5 000). allWarehouses=true exige warehouse:all.")
	ResponseEntity<PageResponse<PurchaseOrderResponse>> list(@ParameterObject PurchaseOrderFilter filter,
			@CurrentWarehouse WarehouseContext warehouse, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size,
			@Parameter(description = "createdAt | number | supplierName | totalAmount | expectedDeliveryDate | status, "
					+ "asc | desc") @RequestParam(required = false) List<String> sort) {
		UUID scope = warehouse.id();
		if (Boolean.TRUE.equals(filter.allWarehouses())) {
			if (!this.warehouseAccess.seesAllWarehouses()) {
				throw new ForbiddenOperationException(ErrorCode.WAREHOUSE_ACCESS_DENIED,
						ErrorCode.WAREHOUSE_ACCESS_DENIED.defaultMessage());
			}
			scope = null;
		}
		PageQuery query = new PageQuery(page, size, sort);
		return PageResponse.toResponse(this.orders.search(filter, scope, query), query);
	}

	@GetMapping("/{id}")
	@RequiresPermission(Permissions.PURCHASE_READ)
	@Operation(summary = "Consulter une commande fournisseur")
	PurchaseOrderResponse get(@PathVariable UUID id) {
		return this.orders.get(id);
	}

	@GetMapping("/{id}/receipts")
	@RequiresPermission(Permissions.PURCHASE_READ)
	@Operation(summary = "Réceptions d'une commande", description = "Avec le mouvement de stock de chaque ligne reçue.")
	List<GoodsReceiptResponse> receipts(@PathVariable UUID id) {
		return this.orders.receipts(id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Audited(action = "CREATE", entityType = "PURCHASE_ORDER", idParam = "")
	@RequiresPermission(Permissions.PURCHASE_CREATE)
	@Operation(summary = "Créer une commande fournisseur",
			description = "Totaux calculés par le serveur ; coût unitaire = unitPurchasePrice, sinon prix d'achat du "
					+ "produit. Livraison dans le dépôt courant sauf warehouseId explicite.")
	ResponseEntity<PurchaseOrderResponse> create(@Valid @RequestBody CreatePurchaseOrderRequest request,
			@CurrentWarehouse WarehouseContext warehouse) {
		PurchaseOrderResponse created = this.orders.create(request, warehouse.id());
		return ResponseEntity.created(URI.create("/api/purchases/orders/" + created.id())).body(created);
	}

	@PostMapping("/{id}/receive")
	@Audited(action = "RECEIVE", entityType = "PURCHASE_ORDER")
	@RequiresPermission(Permissions.PURCHASE_RECEIVE)
	@Operation(summary = "Réceptionner une commande",
			description = "Corps vide ({}) : tout le reste à recevoir. lines : réception partielle. Entrée en stock "
					+ "(mouvements RECEIPT) dans le dépôt de la commande, dans la même transaction.")
	PurchaseOrderResponse receive(@PathVariable UUID id,
			@Valid @RequestBody(required = false) ReceivePurchaseOrderRequest request) {
		return this.receipts.receive(id,
				request != null ? request : new ReceivePurchaseOrderRequest(null, null, null, null));
	}

	@PostMapping("/{id}/cancel")
	@Audited(action = "CANCEL", entityType = "PURCHASE_ORDER")
	@RequiresPermission(Permissions.PURCHASE_CREATE)
	@Operation(summary = "Annuler une commande", description = "Uniquement si rien n'a été reçu.")
	PurchaseOrderResponse cancel(@PathVariable UUID id,
			@Valid @RequestBody(required = false) ClosePurchaseOrderRequest request) {
		return this.orders.cancel(id, request);
	}

	@PostMapping("/{id}/close")
	@Audited(action = "CLOSE", entityType = "PURCHASE_ORDER")
	@RequiresPermission(Permissions.PURCHASE_CREATE)
	@Operation(summary = "Solder une commande", description = "Commande reçue en partie : le reliquat n'est plus attendu.")
	PurchaseOrderResponse close(@PathVariable UUID id,
			@Valid @RequestBody(required = false) ClosePurchaseOrderRequest request) {
		return this.orders.closeRemainder(id, request);
	}

	@PostMapping("/{id}/invoice")
	@ResponseStatus(HttpStatus.CREATED)
	@Audited(action = "INVOICE", entityType = "PURCHASE_ORDER")
	@RequiresPermission(Permissions.PURCHASE_PAY)
	@Operation(summary = "Enregistrer la facture du fournisseur",
			description = "Commande reçue ou soldée, une seule facture, figée. Montant facturé = valeur reçue ; numéro "
					+ "unique par fournisseur ; date ni future ni antérieure à la commande.")
	ResponseEntity<SupplierInvoiceResponse> invoice(@PathVariable UUID id,
			@Valid @RequestBody SupplierInvoiceRequest request) {
		SupplierInvoiceResponse created = this.orders.invoice(id, request);
		return ResponseEntity.created(URI.create("/api/purchases/orders/" + id)).body(created);
	}

}
