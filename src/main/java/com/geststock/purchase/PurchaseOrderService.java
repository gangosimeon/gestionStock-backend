package com.geststock.purchase;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.geststock.audit.AuditEvent;
import com.geststock.audit.AuditService;
import com.geststock.common.api.PageQuery;
import com.geststock.common.api.SortWhitelist;
import com.geststock.common.security.ActorIdentity;
import com.geststock.exception.BusinessRuleException;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.InvalidRequestException;
import com.geststock.exception.NotFoundException;
import com.geststock.product.ProductService;
import com.geststock.product.ProductSnapshot;
import com.geststock.purchase.dto.ClosePurchaseOrderRequest;
import com.geststock.purchase.dto.CreatePurchaseOrderRequest;
import com.geststock.purchase.dto.GoodsReceiptResponse;
import com.geststock.purchase.dto.PurchaseOrderFilter;
import com.geststock.purchase.dto.PurchaseOrderResponse;
import com.geststock.purchase.dto.SupplierPurchaseHistoryResponse;
import com.geststock.supplier.SupplierService;
import com.geststock.supplier.SupplierSnapshot;
import com.geststock.warehouse.WarehouseAccess;

/**
 * Commandes fournisseurs (API publique du module {@code purchase}) : création, consultation,
 * annulation et solde du reliquat. La réception, qui alimente le stock, relève de
 * {@link GoodsReceiptService}.
 *
 * <p>Règles de création (RG-PUR-1) :
 * <ul>
 * <li>fournisseur existant et actif ; produits existants et actifs, chacun sur une seule
 * ligne ; quantités entières positives ;</li>
 * <li>coût unitaire : celui saisi (négocié avec le fournisseur, ≥ 0, deux décimales au plus),
 * sinon le prix d'achat de référence du produit ;</li>
 * <li>total de ligne et total de commande <b>calculés par le serveur</b> ;</li>
 * <li>dépôt de livraison : dépôt courant, ou dépôt désigné s'il est accessible ;</li>
 * <li>livraison prévue : date saisie, sinon aujourd'hui + délai de livraison du fournisseur.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class PurchaseOrderService {

	static final SortWhitelist SORTS = new SortWhitelist(Map.of(
			"createdAt", "createdAt",
			"number", "number",
			"supplierName", "supplierName",
			"totalAmount", "totalAmount",
			"expectedDeliveryDate", "expectedDeliveryDate",
			"status", "status"), Sort.by(Sort.Direction.DESC, "createdAt"));

	/** Plafond de {@code numeric(14,2)}. */
	static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999.99");

	private final PurchaseOrderRepository orders;

	private final GoodsReceiptRepository receipts;

	private final SupplierService suppliers;

	private final ProductService products;

	private final WarehouseAccess warehouseAccess;

	private final AuditService audit;

	private final Clock clock;

	PurchaseOrderService(PurchaseOrderRepository orders, GoodsReceiptRepository receipts, SupplierService suppliers,
			ProductService products, WarehouseAccess warehouseAccess, AuditService audit, Clock clock) {
		this.orders = orders;
		this.receipts = receipts;
		this.suppliers = suppliers;
		this.products = products;
		this.warehouseAccess = warehouseAccess;
		this.audit = audit;
		this.clock = clock;
	}

	/**
	 * Commandes d'un dépôt ({@code warehouseId}), ou de tous si {@code null} (l'appelant a
	 * vérifié {@code warehouse:all}).
	 */
	public Page<PurchaseOrderResponse> search(PurchaseOrderFilter filter, UUID warehouseId, PageQuery query) {
		Specification<PurchaseOrder> spec = Specification.unrestricted();
		if (warehouseId != null) {
			spec = spec.and((root, q, cb) -> cb.equal(root.get("warehouseId"), warehouseId));
		}
		if (filter.status() != null) {
			spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), filter.status()));
		}
		if (filter.supplierId() != null) {
			spec = spec.and((root, q, cb) -> cb.equal(root.get("supplierId"), filter.supplierId()));
		}
		if (StringUtils.hasText(filter.q())) {
			String text = filter.q().trim().toLowerCase(Locale.ROOT);
			String pattern = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
			spec = spec.and((root, q, cb) -> cb.or(
					cb.like(cb.lower(root.get("number")), pattern, '\\'),
					cb.like(cb.lower(root.get("supplierName")), pattern, '\\')));
		}
		return this.orders.findAll(spec, query.toPageable(SORTS)).map(PurchaseMapper::toResponse);
	}

	public PurchaseOrderResponse get(UUID id) {
		return PurchaseMapper.toResponse(loadVisible(id));
	}

	/** Réceptions d'une commande, de la plus ancienne à la plus récente. */
	public List<GoodsReceiptResponse> receipts(UUID orderId) {
		loadVisible(orderId);
		List<GoodsReceipt> found = this.receipts.findByOrder(orderId);
		if (found.isEmpty()) {
			return List.of();
		}
		Map<UUID, List<GoodsReceiptLine>> lines = this.receipts
			.findLines(found.stream().map(GoodsReceipt::getId).toList())
			.stream()
			.collect(Collectors.groupingBy(line -> line.getReceipt().getId()));
		return found.stream()
			.map(receipt -> PurchaseMapper.toResponse(receipt, lines.getOrDefault(receipt.getId(), List.of())))
			.toList();
	}

	/** Historique d'achats d'un fournisseur : ses réceptions, dans un dépôt ou dans tous. */
	public Page<SupplierPurchaseHistoryResponse> supplierHistory(UUID supplierId, UUID warehouseId, PageQuery query) {
		this.suppliers.getForAssignment(supplierId, false);
		return this.receipts
			.findSupplierHistory(supplierId, warehouseId, query.toPageable(GoodsReceiptService.HISTORY_SORTS))
			.map(PurchaseMapper::toHistory);
	}

	@Transactional
	public PurchaseOrderResponse create(CreatePurchaseOrderRequest request, UUID currentWarehouseId) {
		SupplierSnapshot supplier = this.suppliers.getForAssignment(request.supplierId(), true);
		UUID warehouseId = request.warehouseId() != null
				? this.warehouseAccess.requireAccessible(request.warehouseId()).id() : currentWarehouseId;

		Set<UUID> productIds = new HashSet<>();
		for (CreatePurchaseOrderRequest.Line line : request.lines()) {
			if (!productIds.add(line.productId())) {
				throw new InvalidRequestException(ErrorCode.PURCHASE_DUPLICATE_PRODUCT,
						ErrorCode.PURCHASE_DUPLICATE_PRODUCT.defaultMessage());
			}
		}
		Map<UUID, ProductSnapshot> catalog = this.products.snapshots(productIds);
		for (UUID productId : productIds) {
			ProductSnapshot product = catalog.get(productId);
			if (product == null) {
				throw new NotFoundException(ErrorCode.PRODUCT_NOT_FOUND, ErrorCode.PRODUCT_NOT_FOUND.defaultMessage());
			}
			if (!product.active()) {
				throw new BusinessRuleException(ErrorCode.PRODUCT_INACTIVE,
						"Le produit " + product.sku() + " est désactivé : il ne peut plus être commandé.");
			}
		}

		LocalDate expected = request.expectedDeliveryDate() != null ? request.expectedDeliveryDate()
				: LocalDate.now(this.clock).plusDays(supplier.deliveryLeadTimeDays());
		PurchaseOrder order = new PurchaseOrder(nextNumber(), supplier.id(), supplier.name(), warehouseId, expected,
				blankToNull(request.supplierReference()), blankToNull(request.note()));
		for (CreatePurchaseOrderRequest.Line line : request.lines()) {
			ProductSnapshot product = catalog.get(line.productId());
			BigDecimal unitCost = line.unitPurchasePrice() != null ? line.unitPurchasePrice() : product.purchasePrice();
			order.addLine(product.id(), product.sku(), product.name(), line.quantity(), unitCost);
		}
		if (order.getTotalAmount().compareTo(MAX_AMOUNT) > 0) {
			throw new InvalidRequestException(ErrorCode.PURCHASE_AMOUNT_OUT_OF_RANGE,
					ErrorCode.PURCHASE_AMOUNT_OUT_OF_RANGE.defaultMessage());
		}
		PurchaseOrderResponse created = PurchaseMapper.toResponse(this.orders.saveAndFlush(order));
		this.audit.record(AuditEvent.of("CREATE", "PURCHASE_ORDER", order.getId(), order.getNumber())
			.details("Commande " + order.getNumber() + " à " + order.getSupplierName() + " : "
					+ order.getTotalAmount().toPlainString())
			.after(created)
			.warehouse(warehouseId));
		return created;
	}

	/** Annule une commande dont rien n'a été reçu. */
	@Transactional
	public PurchaseOrderResponse cancel(UUID id, ClosePurchaseOrderRequest request) {
		PurchaseOrder order = loadForUpdate(id);
		PurchaseOrderStatus before = order.getStatus();
		order.cancel(Instant.now(this.clock), ActorIdentity.currentUserId().orElse(null), reasonOf(request));
		PurchaseOrderResponse saved = PurchaseMapper.toResponse(this.orders.saveAndFlush(order));
		auditClosing("CANCEL", order, before, "Annulation de la commande " + order.getNumber(), request);
		return saved;
	}

	/** Solde une commande reçue en partie : le reliquat n'est plus attendu. */
	@Transactional
	public PurchaseOrderResponse closeRemainder(UUID id, ClosePurchaseOrderRequest request) {
		PurchaseOrder order = loadForUpdate(id);
		PurchaseOrderStatus before = order.getStatus();
		order.closeRemainder(Instant.now(this.clock), ActorIdentity.currentUserId().orElse(null), reasonOf(request));
		PurchaseOrderResponse saved = PurchaseMapper.toResponse(this.orders.saveAndFlush(order));
		auditClosing("CLOSE", order, before, "Solde de la commande " + order.getNumber() + " (reliquat abandonné)",
				request);
		return saved;
	}

	private void auditClosing(String action, PurchaseOrder order, PurchaseOrderStatus before, String details,
			ClosePurchaseOrderRequest request) {
		String reason = reasonOf(request);
		this.audit.record(AuditEvent.of(action, "PURCHASE_ORDER", order.getId(), order.getNumber())
			.details(details + (reason != null ? " — " + reason : ""))
			.before(Map.of("status", before))
			.after(Map.of("status", order.getStatus()))
			.warehouse(order.getWarehouseId()));
	}

	// --- Outils (partagés avec la réception) --------------------------------------------

	/** Commande verrouillée, visible et dans un dépôt accessible à l'utilisateur. */
	PurchaseOrder loadForUpdate(UUID id) {
		PurchaseOrder order = this.orders.findForUpdate(id).orElseThrow(PurchaseOrderService::notFound);
		requireVisible(order);
		return order;
	}

	private PurchaseOrder loadVisible(UUID id) {
		PurchaseOrder order = this.orders.findById(id).orElseThrow(PurchaseOrderService::notFound);
		requireVisible(order);
		return order;
	}

	/** Une commande d'un dépôt hors périmètre est « introuvable » (pas de fuite d'existence). */
	private void requireVisible(PurchaseOrder order) {
		if (!this.warehouseAccess.canSee(order.getWarehouseId())) {
			throw notFound();
		}
	}

	private String nextNumber() {
		return "CMD-" + LocalDate.now(this.clock).getYear() + "-"
				+ String.format(Locale.ROOT, "%06d", this.orders.nextNumber());
	}

	private static NotFoundException notFound() {
		return new NotFoundException(ErrorCode.PURCHASE_ORDER_NOT_FOUND,
				ErrorCode.PURCHASE_ORDER_NOT_FOUND.defaultMessage());
	}

	private static String reasonOf(ClosePurchaseOrderRequest request) {
		return request != null ? blankToNull(request.reason()) : null;
	}

	static String blankToNull(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

}
