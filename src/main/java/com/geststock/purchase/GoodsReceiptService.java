package com.geststock.purchase;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.geststock.audit.AuditEvent;
import com.geststock.audit.AuditService;
import com.geststock.common.api.SortWhitelist;
import com.geststock.common.security.ActorIdentity;
import com.geststock.exception.BusinessRuleException;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.InvalidRequestException;
import com.geststock.exception.UnauthorizedException;
import com.geststock.product.ProductService;
import com.geststock.purchase.dto.PurchaseOrderResponse;
import com.geststock.purchase.dto.ReceivePurchaseOrderRequest;
import com.geststock.stock.StockMovement;
import com.geststock.stock.StockMovementCommand;
import com.geststock.stock.StockMovementType;
import com.geststock.stock.StockService;
import com.geststock.stock.StockSourceType;
import com.geststock.warehouse.WarehouseAccess;

/**
 * <b>Réception des commandes fournisseurs : seul point d'entrée en stock des achats.</b>
 *
 * <p>Une réception validée s'exécute dans <b>une seule transaction</b> :
 * <ol>
 * <li>verrou de la commande ({@code SELECT … FOR UPDATE}) : deux réceptions de la même
 * commande se sérialisent, la seconde voit la première ;</li>
 * <li>contrôles : commande réceptionnable, dépôt de livraison accessible et actif, produits
 * présents sur la commande, quantité ≤ reste à recevoir (pas de sur-réception) ;</li>
 * <li>écriture du bon de réception (numéro {@code REC-AAAA-nnnnnn}) ;</li>
 * <li><b>entrée en stock</b> par {@link StockService#applyMovements} : un mouvement
 * {@code RECEIPT} par ligne, dans le dépôt de la commande, au coût unitaire de la commande,
 * avec la réception pour source ;</li>
 * <li>lignes reçues, chacune liée à son mouvement ; quantités reçues des lignes de commande,
 * valeur reçue et statut de la commande ;</li>
 * <li>dernier prix d'achat des produits (RG-PUR-5).</li>
 * </ol>
 * Au moindre échec, tout est annulé. La base vérifie en outre qu'aucune ligne reçue n'existe
 * sans son mouvement de stock, et qu'aucune quantité reçue ne diffère des lignes reçues
 * (V015) : une réception qui n'alimenterait pas le stock est impossible.
 *
 * <p>Le coût est celui négocié à la commande : il n'est jamais saisi à la réception. La
 * valorisation au coût moyen pondéré (décision I.9) reste à trancher ; le coût de chaque
 * entrée est conservé dans son mouvement de stock pour la permettre.
 */
@Service
@Transactional
public class GoodsReceiptService {

	static final SortWhitelist HISTORY_SORTS = new SortWhitelist(
			Map.of("purchaseDateIso", "receivedAt", "receivedAt", "receivedAt", "totalAmount", "totalAmount"),
			Sort.by(Sort.Direction.DESC, "receivedAt"));

	private final PurchaseOrderService orders;

	private final PurchaseOrderRepository orderRepository;

	private final GoodsReceiptRepository receipts;

	private final StockService stock;

	private final ProductService products;

	private final WarehouseAccess warehouseAccess;

	private final EntityManager entityManager;

	private final AuditService audit;

	private final Clock clock;

	GoodsReceiptService(PurchaseOrderService orders, PurchaseOrderRepository orderRepository,
			GoodsReceiptRepository receipts, StockService stock, ProductService products,
			WarehouseAccess warehouseAccess, EntityManager entityManager, AuditService audit, Clock clock) {
		this.orders = orders;
		this.orderRepository = orderRepository;
		this.receipts = receipts;
		this.stock = stock;
		this.products = products;
		this.warehouseAccess = warehouseAccess;
		this.entityManager = entityManager;
		this.audit = audit;
		this.clock = clock;
	}

	/**
	 * Réceptionne tout ou partie d'une commande et l'entre en stock, depuis le dépôt de la commande.
	 *
	 * @return la commande mise à jour (contrat du front)
	 * @throws com.geststock.exception.ConflictException {@code PURCHASE_ORDER_NOT_RECEIVABLE},
	 *                                                   {@code WAREHOUSE_MISMATCH}
	 * @throws InvalidRequestException {@code RECEIPT_PRODUCT_NOT_ORDERED},
	 *                                 {@code PURCHASE_DUPLICATE_PRODUCT}, {@code UNKNOWN_WAREHOUSE}
	 * @throws BusinessRuleException   {@code RECEIPT_QUANTITY_EXCEEDS_REMAINING}
	 */
	public PurchaseOrderResponse receive(UUID orderId, ReceivePurchaseOrderRequest request, UUID currentWarehouseId) {
		UUID actor = ActorIdentity.currentUserId().orElseThrow(() -> new UnauthorizedException(ErrorCode.UNAUTHENTICATED));
		PurchaseOrder order = this.orders.loadForUpdate(orderId, currentWarehouseId);
		if (!order.getStatus().isReceivable()) {
			throw PurchaseOrder.notReceivable();
		}
		this.warehouseAccess.requireAccessible(order.getWarehouseId());

		PurchaseOrderStatus statusBefore = order.getStatus();
		BigDecimal receivedBefore = order.getReceivedAmount();
		Map<PurchaseOrderLine, Integer> plan = plan(order, request);
		int totalQuantity = plan.values().stream().mapToInt(Integer::intValue).sum();
		BigDecimal totalAmount = plan.entrySet().stream()
			.map(entry -> entry.getKey().amountOf(entry.getValue()))
			.reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);

		Instant now = Instant.now(this.clock);
		Instant receivedAt = request.receivedAt() != null ? request.receivedAt() : now;
		GoodsReceipt receipt = this.receipts.save(new GoodsReceipt(nextNumber(), order, receivedAt,
				PurchaseOrderService.blankToNull(request.supplierDeliveryNote()),
				PurchaseOrderService.blankToNull(request.note()), totalQuantity, totalAmount, now, actor));
		this.entityManager.flush();

		String reason = "Réception " + receipt.getNumber() + " (commande " + order.getNumber() + ")";
		List<StockMovementCommand> commands = new ArrayList<>(plan.size());
		plan.forEach((line, quantity) -> commands.add(StockMovementCommand
			.of(order.getWarehouseId(), line.getProductId(), StockMovementType.RECEIPT, quantity,
					StockSourceType.PURCHASE_ORDER, reason)
			.withSource(receipt.getId(), receipt.getNumber())
			.withUnitCost(line.getUnitCost())));
		List<StockMovement> movements = this.stock.applyMovements(commands);

		int index = 0;
		Map<UUID, BigDecimal> unitCosts = new LinkedHashMap<>();
		for (Map.Entry<PurchaseOrderLine, Integer> entry : plan.entrySet()) {
			PurchaseOrderLine line = entry.getKey();
			GoodsReceiptLine receiptLine = new GoodsReceiptLine(receipt, index + 1, line, entry.getValue(),
					movements.get(index).getId());
			this.entityManager.persist(receiptLine);
			line.receive(entry.getValue());
			unitCosts.put(line.getProductId(), line.getUnitCost());
			index++;
		}
		this.products.recordPurchasePrices(unitCosts);
		order.recordReceipt(totalAmount, receivedAt);
		this.entityManager.flush();

		List<Map<String, Object>> received = new ArrayList<>(plan.size());
		plan.forEach((line, quantity) -> received.add(Map.of("productSku", line.getProductSku(), "quantity", quantity,
				"unitCost", line.getUnitCost())));
		this.audit.record(AuditEvent.of("RECEIVE", "GOODS_RECEIPT", receipt.getId(), receipt.getNumber())
			.details("Réception " + receipt.getNumber() + " de la commande " + order.getNumber() + " : " + totalQuantity
					+ " article(s), " + totalAmount.toPlainString() + " ; entrées en stock et prix d'achat mis à jour")
			.before(Map.of("orderStatus", statusBefore, "receivedAmount", receivedBefore))
			.after(Map.of("orderStatus", order.getStatus(), "receivedAmount", order.getReceivedAmount()))
			.meta("purchaseOrderId", order.getId())
			.meta("purchaseOrderNumber", order.getNumber())
			.meta("lines", received)
			.warehouse(order.getWarehouseId()));
		return PurchaseMapper.toResponse(this.orderRepository.save(order));
	}

	/**
	 * Quantités à recevoir par ligne : tout le reste si aucune ligne n'est indiquée, sinon les
	 * quantités demandées, contrôlées contre le reste à recevoir.
	 */
	private static Map<PurchaseOrderLine, Integer> plan(PurchaseOrder order, ReceivePurchaseOrderRequest request) {
		Map<PurchaseOrderLine, Integer> plan = new LinkedHashMap<>();
		if (request.lines() == null || request.lines().isEmpty()) {
			order.getLines().stream()
				.filter(line -> line.remainingQuantity() > 0)
				.forEach(line -> plan.put(line, line.remainingQuantity()));
			return plan;
		}
		Map<UUID, PurchaseOrderLine> byProduct = new LinkedHashMap<>();
		order.getLines().forEach(line -> byProduct.put(line.getProductId(), line));
		for (ReceivePurchaseOrderRequest.Line requested : request.lines()) {
			PurchaseOrderLine line = byProduct.get(requested.productId());
			if (line == null) {
				throw new InvalidRequestException(ErrorCode.RECEIPT_PRODUCT_NOT_ORDERED,
						ErrorCode.RECEIPT_PRODUCT_NOT_ORDERED.defaultMessage());
			}
			if (plan.containsKey(line)) {
				throw new InvalidRequestException(ErrorCode.PURCHASE_DUPLICATE_PRODUCT,
						ErrorCode.PURCHASE_DUPLICATE_PRODUCT.defaultMessage());
			}
			if (requested.quantity() > line.remainingQuantity()) {
				Map<String, Object> details = new LinkedHashMap<>();
				details.put("productId", line.getProductId());
				details.put("productSku", line.getProductSku());
				details.put("remaining", line.remainingQuantity());
				details.put("requested", requested.quantity());
				throw new BusinessRuleException(ErrorCode.RECEIPT_QUANTITY_EXCEEDS_REMAINING,
						"Quantité reçue supérieure au reste à recevoir pour " + line.getProductSku() + " ("
								+ line.remainingQuantity() + ").",
						details);
			}
			plan.put(line, requested.quantity());
		}
		return plan;
	}

	private String nextNumber() {
		return "REC-" + LocalDate.now(this.clock).getYear() + "-"
				+ String.format(Locale.ROOT, "%06d", this.receipts.nextNumber());
	}

}
