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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.geststock.audit.AuditEvent;
import com.geststock.audit.AuditService;
import com.geststock.cash.CashService;
import com.geststock.cash.PaymentMethod;
import com.geststock.common.api.PageQuery;
import com.geststock.common.api.SortWhitelist;
import com.geststock.common.security.ActorIdentity;
import com.geststock.exception.BusinessRuleException;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.InvalidRequestException;
import com.geststock.purchase.dto.PurchaseOrderPaymentRequest;
import com.geststock.purchase.dto.PurchaseOrderResponse;
import com.geststock.purchase.dto.SupplierAccountResponse;
import com.geststock.purchase.dto.SupplierPaymentRequest;
import com.geststock.purchase.dto.SupplierPaymentResponse;
import com.geststock.supplier.SupplierService;
import com.geststock.supplier.SupplierSnapshot;
import com.geststock.warehouse.WarehouseAccess;

/**
 * Paiements fournisseurs (V025, {@code docs/supplier-payments.md}).
 *
 * <p>Un paiement, dans <b>une transaction</b> : commandes verrouillées ({@code FOR UPDATE},
 * toujours de la plus ancienne à la plus récente), imputation sur la commande désignée ou sur
 * les plus anciennes ayant un reste à payer, refus au-delà du reste dû (pas de trop-payé), puis,
 * en espèces seulement, décaissement dans la caisse ouverte de l'utilisateur. La base vérifie au
 * COMMIT l'imputation complète, le montant payé des commandes et le lien avec la caisse.
 */
@Service
@Transactional(readOnly = true)
public class SupplierPaymentService {

	static final SortWhitelist SORTS = new SortWhitelist(
			Map.of("paymentDate", "paymentDate", "createdAt", "createdAt", "amount", "amount", "number", "number"),
			Sort.by(Sort.Direction.DESC, "paymentDate").and(Sort.by(Sort.Direction.DESC, "createdAt")));

	private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

	private final SupplierPaymentRepository payments;

	private final SupplierPaymentAllocationRepository allocations;

	private final PurchaseOrderRepository orders;

	private final PurchaseOrderService orderService;

	private final SupplierService suppliers;

	private final CashService cash;

	private final WarehouseAccess warehouseAccess;

	private final AuditService audit;

	private final Clock clock;

	SupplierPaymentService(SupplierPaymentRepository payments, SupplierPaymentAllocationRepository allocations,
			PurchaseOrderRepository orders, PurchaseOrderService orderService, SupplierService suppliers,
			CashService cash, WarehouseAccess warehouseAccess, AuditService audit, Clock clock) {
		this.payments = payments;
		this.allocations = allocations;
		this.orders = orders;
		this.orderService = orderService;
		this.suppliers = suppliers;
		this.cash = cash;
		this.warehouseAccess = warehouseAccess;
		this.audit = audit;
		this.clock = clock;
	}

	/** Paiements d'un fournisseur, des plus récents aux plus anciens. */
	public Page<SupplierPaymentResponse> list(UUID supplierId, String text, PageQuery query) {
		this.suppliers.getForAssignment(supplierId, false);
		Page<SupplierPayment> page = this.payments.findBySupplier(supplierId,
				com.geststock.common.api.SearchText.pattern(text), query.toPageable(SORTS));
		Map<UUID, List<SupplierPaymentResponse.Allocation>> allocated = allocationsOf(
				page.getContent().stream().map(SupplierPayment::getId).toList());
		return page.map(p -> toResponse(p, allocated.getOrDefault(p.getId(), List.of())));
	}

	/** Compte du fournisseur (commandé, reçu, dû, payé, reste à payer) sur les dépôts visibles. */
	public SupplierAccountResponse account(UUID supplierId) {
		SupplierSnapshot supplier = this.suppliers.getForAssignment(supplierId, false);
		Set<UUID> visible = this.warehouseAccess.visibleWarehouseIds();
		boolean all = visible == null;
		List<Object[]> rows = this.orders.accountTotals(supplier.id(), all, all ? Set.of(UUID.randomUUID()) : visible);
		Object[] totals = rows.isEmpty() ? new Object[] { ZERO, ZERO, ZERO, ZERO, 0L } : rows.get(0);
		BigDecimal ordered = money(totals[0]);
		BigDecimal received = money(totals[1]);
		BigDecimal payable = money(totals[2]);
		BigDecimal paid = money(totals[3]);
		return new SupplierAccountResponse(supplier.id(), ordered, received, payable, paid,
				payable.subtract(paid).max(ZERO), paid.subtract(received).max(ZERO), ((Number) totals[4]).longValue());
	}

	/** Paiement d'un fournisseur, imputé sur la commande désignée ou sur les plus anciennes. */
	@Transactional
	public SupplierPaymentResponse pay(UUID supplierId, SupplierPaymentRequest request) {
		SupplierSnapshot supplier = this.suppliers.getForAssignment(supplierId, false);
		LocalDate today = LocalDate.now(this.clock);
		LocalDate paymentDate = request.paymentDate() != null ? request.paymentDate() : today;
		if (paymentDate.isAfter(today)) {
			throw new InvalidRequestException(ErrorCode.SUPPLIER_PAYMENT_DATE_IN_FUTURE,
					ErrorCode.SUPPLIER_PAYMENT_DATE_IN_FUTURE.defaultMessage());
		}
		PaymentMethod method = request.paymentMethod() != null ? request.paymentMethod() : PaymentMethod.CASH;
		BigDecimal amount = request.amount().setScale(2);
		if (method == PaymentMethod.CASH) {
			// Avant toute écriture : caisse ouverte et espèces suffisantes (revérifié à la sortie).
			this.cash.requireCashAvailable(null, amount);
		}

		Map<PurchaseOrder, BigDecimal> split = request.orderId() != null
				? Map.of(orderOf(supplier, request.orderId()), amount)
				: oldestFirst(supplier, amount);
		split.forEach(PurchaseOrder::recordPayment);

		SupplierPayment payment = this.payments.saveAndFlush(new SupplierPayment(nextNumber(), supplier.id(), paymentDate,
				amount, method, trimToNull(request.externalReference()), trimToNull(request.note()),
				Instant.now(this.clock), ActorIdentity.currentUserId().orElse(null)));
		List<SupplierPaymentResponse.Allocation> allocated = new ArrayList<>();
		split.forEach((order, part) -> {
			this.allocations.save(new SupplierPaymentAllocation(payment.getId(), order.getId(), part));
			allocated.add(new SupplierPaymentResponse.Allocation(order.getId(), order.getNumber(), part));
		});
		if (method == PaymentMethod.CASH) {
			this.cash.recordSupplierPayment(null, payment.getId(), payment.getNumber(), amount,
					"Paiement fournisseur " + supplier.name());
		}
		this.orders.flush();

		SupplierPaymentResponse response = toResponse(payment, allocated);
		this.audit.record(AuditEvent.of("PAY", "SUPPLIER_PAYMENT", payment.getId(), payment.getNumber())
			.details("Paiement " + payment.getNumber() + " à " + supplier.name() + " : " + amount.toPlainString() + " ("
					+ method + ") sur " + allocated.stream()
						.map(SupplierPaymentResponse.Allocation::orderNumber)
						.collect(Collectors.joining(", ")))
			.after(response));
		return response;
	}

	/** Paiement d'une commande ({@code POST /purchases/orders/{id}/pay}) ; renvoie la commande à jour. */
	@Transactional
	public PurchaseOrderResponse payOrder(UUID orderId, PurchaseOrderPaymentRequest request) {
		PurchaseOrder order = this.orderService.loadForUpdate(orderId);
		pay(order.getSupplierId(), request.forOrder(orderId));
		return PurchaseMapper.toResponse(order);
	}

	// --- Imputation ------------------------------------------------------------------------

	/** Commande désignée : du fournisseur, visible, verrouillée. */
	private PurchaseOrder orderOf(SupplierSnapshot supplier, UUID orderId) {
		PurchaseOrder order = this.orderService.loadForUpdate(orderId);
		if (!order.getSupplierId().equals(supplier.id())) {
			throw new InvalidRequestException(ErrorCode.SUPPLIER_PAYMENT_ORDER_MISMATCH,
					ErrorCode.SUPPLIER_PAYMENT_ORDER_MISMATCH.defaultMessage());
		}
		return order;
	}

	/**
	 * Paiement global : solde les commandes visibles les plus anciennes ayant un reste à payer.
	 * Refusé s'il dépasse le reste dû au fournisseur.
	 */
	private Map<PurchaseOrder, BigDecimal> oldestFirst(SupplierSnapshot supplier, BigDecimal amount) {
		List<PurchaseOrder> payable = this.orders.findPayableForUpdate(supplier.id())
			.stream()
			.filter(o -> this.warehouseAccess.canSee(o.getWarehouseId()))
			.filter(o -> o.remainingToPay().signum() > 0)
			.toList();
		BigDecimal balance = payable.stream().map(PurchaseOrder::remainingToPay).reduce(ZERO, BigDecimal::add);
		if (amount.compareTo(balance) > 0) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("supplierId", supplier.id());
			details.put("remainingToPay", balance);
			details.put("requested", amount);
			throw new BusinessRuleException(ErrorCode.SUPPLIER_PAYMENT_EXCEEDS_BALANCE,
					ErrorCode.SUPPLIER_PAYMENT_EXCEEDS_BALANCE.defaultMessage(), details);
		}
		Map<PurchaseOrder, BigDecimal> split = new LinkedHashMap<>();
		BigDecimal left = amount;
		for (PurchaseOrder order : payable) {
			if (left.signum() == 0) {
				break;
			}
			BigDecimal part = left.min(order.remainingToPay());
			split.put(order, part);
			left = left.subtract(part);
		}
		return split;
	}

	// --- Outils -----------------------------------------------------------------------------

	private Map<UUID, List<SupplierPaymentResponse.Allocation>> allocationsOf(List<UUID> paymentIds) {
		if (paymentIds.isEmpty()) {
			return Map.of();
		}
		Map<UUID, List<SupplierPaymentResponse.Allocation>> byPayment = new LinkedHashMap<>();
		for (Object[] row : this.payments.allocations(paymentIds)) {
			byPayment.computeIfAbsent((UUID) row[0], id -> new ArrayList<>())
				.add(new SupplierPaymentResponse.Allocation((UUID) row[1], (String) row[2], money(row[3])));
		}
		return byPayment;
	}

	private static SupplierPaymentResponse toResponse(SupplierPayment payment,
			List<SupplierPaymentResponse.Allocation> allocated) {
		UUID singleOrder = allocated.size() == 1 ? allocated.get(0).orderId() : null;
		return new SupplierPaymentResponse(payment.getId(), payment.getNumber(), payment.getSupplierId(),
				payment.getPaymentDate(), payment.getAmount(), payment.getPaymentMethod(), singleOrder,
				List.copyOf(allocated), payment.getExternalReference(), payment.getNote(), payment.getCreatedAt(),
				payment.getCreatedBy());
	}

	private String nextNumber() {
		return "PAF-" + LocalDate.now(this.clock).getYear() + "-"
				+ String.format(Locale.ROOT, "%06d", this.payments.nextNumber());
	}

	private static BigDecimal money(Object value) {
		return value == null ? ZERO : new BigDecimal(value.toString()).setScale(2);
	}

	private static String trimToNull(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

}
