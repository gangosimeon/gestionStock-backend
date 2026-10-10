package com.geststock.returns;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.geststock.audit.AuditService;
import com.geststock.cash.CashService;
import com.geststock.cash.PaymentMethod;
import com.geststock.common.api.PageQuery;
import com.geststock.common.api.SortWhitelist;
import com.geststock.common.security.ActorIdentity;
import com.geststock.customer.CustomerAccountEntryType;
import com.geststock.customer.CustomerAccountService;
import com.geststock.exception.BusinessRuleException;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.InvalidRequestException;
import com.geststock.exception.NotFoundException;
import com.geststock.exception.UnauthorizedException;
import com.geststock.returns.dto.CreateCustomerReturnRequest;
import com.geststock.returns.dto.CustomerReturnFilter;
import com.geststock.returns.dto.CustomerReturnResponse;
import com.geststock.sale.SaleService;
import com.geststock.sale.SaleSnapshot;
import com.geststock.stock.StockMovement;
import com.geststock.stock.StockMovementCommand;
import com.geststock.stock.StockMovementType;
import com.geststock.stock.StockService;
import com.geststock.stock.StockSourceType;
import com.geststock.warehouse.WarehouseAccess;

/**
 * Retours clients et avoirs (API publique du module {@code returns}).
 *
 * <p>Un retour, dans <b>une transaction</b> :
 * <ol>
 * <li>vente verrouillée (deux retours de la même vente se sérialisent), non annulée ;</li>
 * <li>quantités contrôlées : jamais plus que vendu, cumul des retours précédents compris ;
 * montant au <b>prix net de la vente</b> (remise comprise), la dernière unité reprenant le
 * reliquat pour éviter les écarts d'arrondi ;</li>
 * <li>avoir {@code RET-AAAA-nnnnnn} enregistré, avec son motif et son auteur ;</li>
 * <li>articles revendables remis en stock ({@code CUSTOMER_RETURN}, au coût de la vente) ;
 * défectueux : pas de remise en stock ;</li>
 * <li>règlement : remboursement depuis la caisse de l'utilisateur (dans la limite de ce que le
 * client a payé sur la vente), ou avoir imputé sur la dette du client (dans la limite de son
 * solde dû) ;</li>
 * <li>trace d'audit.</li>
 * </ol>
 * La vente d'origine n'est jamais modifiée : elle garde son historique, ses retours s'y
 * rattachent ; elle ne peut plus être annulée ensuite.
 */
@Service
@Transactional(readOnly = true)
public class CustomerReturnService {

	static final SortWhitelist SORTS = new SortWhitelist(Map.of("createdAt", "createdAt", "totalAmount", "totalAmount",
			"number", "number"), Sort.by(Sort.Direction.DESC, "createdAt"));

	private final CustomerReturnRepository returns;

	private final SaleService sales;

	private final StockService stock;

	private final CashService cash;

	private final CustomerAccountService accounts;

	private final WarehouseAccess warehouseAccess;

	private final AuditService audit;

	private final EntityManager entityManager;

	private final Clock clock;

	CustomerReturnService(CustomerReturnRepository returns, SaleService sales, StockService stock, CashService cash,
			CustomerAccountService accounts, WarehouseAccess warehouseAccess, AuditService audit,
			EntityManager entityManager, Clock clock) {
		this.returns = returns;
		this.sales = sales;
		this.stock = stock;
		this.cash = cash;
		this.accounts = accounts;
		this.warehouseAccess = warehouseAccess;
		this.audit = audit;
		this.entityManager = entityManager;
		this.clock = clock;
	}

	// --- Consultation ------------------------------------------------------------------------

	public Page<CustomerReturnResponse> search(CustomerReturnFilter filter, UUID warehouseId, PageQuery query) {
		Specification<CustomerReturn> spec = Specification.unrestricted();
		if (warehouseId != null) {
			spec = spec.and((root, q, cb) -> cb.equal(root.get("warehouseId"), warehouseId));
		}
		if (filter.customerId() != null) {
			spec = spec.and((root, q, cb) -> cb.equal(root.get("customerId"), filter.customerId()));
		}
		if (filter.settlement() != null) {
			spec = spec.and((root, q, cb) -> cb.equal(root.get("settlement"), filter.settlement()));
		}
		if (filter.from() != null) {
			spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), filter.from()));
		}
		if (filter.to() != null) {
			spec = spec.and((root, q, cb) -> cb.lessThan(root.get("createdAt"), filter.to()));
		}
		Page<CustomerReturn> page = this.returns.findAll(spec, query.toPageable(SORTS));
		Map<UUID, List<CustomerReturnLine>> lines = linesOf(page.getContent());
		return page.map(r -> toResponse(r, lines.getOrDefault(r.getId(), List.of())));
	}

	public CustomerReturnResponse get(UUID id) {
		CustomerReturn customerReturn = this.returns.findById(id)
			.filter(r -> this.warehouseAccess.canSee(r.getWarehouseId()))
			.orElseThrow(() -> new NotFoundException(ErrorCode.RETURN_NOT_FOUND, ErrorCode.RETURN_NOT_FOUND.defaultMessage()));
		return toResponse(customerReturn, linesOf(List.of(customerReturn)).getOrDefault(id, List.of()));
	}

	/** Retours d'une vente, du plus ancien au plus récent. */
	public List<CustomerReturnResponse> forSale(UUID saleId) {
		this.sales.get(saleId);
		List<CustomerReturn> found = this.returns.findBySale(saleId);
		Map<UUID, List<CustomerReturnLine>> lines = linesOf(found);
		return found.stream().map(r -> toResponse(r, lines.getOrDefault(r.getId(), List.of()))).toList();
	}

	// --- Retour -------------------------------------------------------------------------------

	@Transactional
	public CustomerReturnResponse create(UUID saleId, CreateCustomerReturnRequest request) {
		UUID actor = ActorIdentity.currentUserId()
			.orElseThrow(() -> new UnauthorizedException(ErrorCode.UNAUTHENTICATED));
		SaleSnapshot sale = this.sales.lockForReturn(saleId);

		// Quantités et montants, au prix net de la vente.
		Map<UUID, SaleSnapshot.Line> soldByProduct = new HashMap<>();
		sale.lines().forEach(line -> soldByProduct.put(line.productId(), line));
		Map<UUID, Object[]> returned = new HashMap<>();
		this.returns.returnedOnSale(saleId).forEach(row -> returned.put((UUID) row[0], row));
		Set<UUID> seen = new LinkedHashSet<>();
		List<Planned> plan = new ArrayList<>();
		BigDecimal total = BigDecimal.ZERO.setScale(2);
		for (CreateCustomerReturnRequest.Item item : request.items()) {
			if (!seen.add(item.productId())) {
				throw new InvalidRequestException(ErrorCode.RETURN_DUPLICATE_PRODUCT,
						ErrorCode.RETURN_DUPLICATE_PRODUCT.defaultMessage());
			}
			SaleSnapshot.Line sold = soldByProduct.get(item.productId());
			if (sold == null) {
				throw new InvalidRequestException(ErrorCode.RETURN_PRODUCT_NOT_SOLD,
						ErrorCode.RETURN_PRODUCT_NOT_SOLD.defaultMessage());
			}
			Object[] already = returned.get(sold.id());
			int returnedQuantity = already != null ? ((Number) already[1]).intValue() : 0;
			BigDecimal returnedAmount = already != null ? (BigDecimal) already[2] : BigDecimal.ZERO;
			int remaining = sold.quantity() - returnedQuantity;
			if (item.quantity() > remaining) {
				Map<String, Object> details = new LinkedHashMap<>();
				details.put("productId", sold.productId());
				details.put("productSku", sold.productSku());
				details.put("sold", sold.quantity());
				details.put("alreadyReturned", returnedQuantity);
				details.put("requested", item.quantity());
				throw new BusinessRuleException(ErrorCode.RETURN_QUANTITY_EXCEEDS_SOLD,
						"Retour de " + item.quantity() + " " + sold.productSku() + " refusé : " + remaining
								+ " restant(s) à retourner sur cette vente.",
						details);
			}
			BigDecimal amount = item.quantity() == remaining ? sold.lineTotal().subtract(returnedAmount)
					: sold.lineTotal().multiply(BigDecimal.valueOf(item.quantity()))
						.divide(BigDecimal.valueOf(sold.quantity()), 2, RoundingMode.HALF_UP);
			plan.add(new Planned(sold, item.quantity(), amount.setScale(2), item.condition()));
			total = total.add(amount);
		}

		// Règlement possible ? (refus avant toute écriture)
		PaymentMethod refundMethod = null;
		if (request.settlement() == ReturnSettlement.REFUND) {
			refundMethod = request.refundMethod() != null ? request.refundMethod()
					: sale.paymentMethod() != null ? sale.paymentMethod() : PaymentMethod.CASH;
			BigDecimal refundable = sale.paidAmount().subtract(this.returns.refundedOnSale(saleId));
			if (total.compareTo(refundable) > 0) {
				throw new BusinessRuleException(ErrorCode.RETURN_REFUND_EXCEEDS_PAID,
						ErrorCode.RETURN_REFUND_EXCEEDS_PAID.defaultMessage(),
						Map.of("refundable", refundable.max(BigDecimal.ZERO), "requested", total));
			}
			this.cash.requireOpenSession(sale.warehouseId());
		}
		else if (sale.customerId() == null) {
			throw new BusinessRuleException(ErrorCode.RETURN_CUSTOMER_REQUIRED,
					ErrorCode.RETURN_CUSTOMER_REQUIRED.defaultMessage());
		}

		// Avoir, remise en stock, lignes, règlement.
		String reason = request.reason().trim();
		CustomerReturn customerReturn = this.returns.save(new CustomerReturn(nextNumber(), saleId, sale.warehouseId(),
				sale.customerId(), request.settlement(), refundMethod, total, reason, Instant.now(this.clock), actor));
		this.entityManager.flush();

		List<Planned> restocked = plan.stream().filter(p -> p.condition() == ItemCondition.RESELLABLE).toList();
		List<StockMovementCommand> commands = new ArrayList<>(restocked.size());
		for (Planned p : restocked) {
			commands.add(StockMovementCommand
				.of(sale.warehouseId(), p.sold().productId(), StockMovementType.CUSTOMER_RETURN, p.quantity(),
						StockSourceType.CUSTOMER_RETURN,
						"Retour " + customerReturn.getNumber() + " (vente " + sale.number() + ")")
				.withSource(customerReturn.getId(), customerReturn.getNumber())
				.withUnitCost(p.sold().unitCost()));
		}
		List<StockMovement> movements = this.stock.applyMovements(commands);
		Map<UUID, UUID> movementByProduct = new HashMap<>();
		for (int i = 0; i < restocked.size(); i++) {
			movementByProduct.put(restocked.get(i).sold().productId(), movements.get(i).getId());
		}

		List<CustomerReturnLine> lines = new ArrayList<>(plan.size());
		for (int i = 0; i < plan.size(); i++) {
			Planned p = plan.get(i);
			CustomerReturnLine line = new CustomerReturnLine(customerReturn.getId(), i + 1, p.sold().id(),
					p.sold().productId(), p.sold().productSku(), p.sold().productName(), p.quantity(), p.amount(),
					p.sold().unitCost(), p.condition(), movementByProduct.get(p.sold().productId()));
			this.entityManager.persist(line);
			lines.add(line);
		}

		if (request.settlement() == ReturnSettlement.REFUND) {
			this.cash.recordReturnRefund(sale.warehouseId(), customerReturn.getId(), customerReturn.getNumber(), total,
					refundMethod, sale.customerId(), reason);
		}
		else {
			this.accounts.recordCredit(sale.customerId(), CustomerAccountEntryType.CREDIT_NOTE, total,
					customerReturn.getId(), customerReturn.getNumber(), reason);
		}
		this.entityManager.flush();

		CustomerReturnResponse response = toResponse(customerReturn, lines);
		this.audit.success("CREATE", "CUSTOMER_RETURN", customerReturn.getId().toString(), customerReturn.getNumber(),
				"Retour " + customerReturn.getNumber() + " sur la vente " + sale.number() + " : " + total.toPlainString()
						+ (request.settlement() == ReturnSettlement.REFUND ? " remboursé (" + refundMethod + ")"
								: " en avoir sur compte")
						+ " — " + reason,
				response, sale.warehouseId());
		return response;
	}

	// --- Outils -------------------------------------------------------------------------------

	private Map<UUID, List<CustomerReturnLine>> linesOf(List<CustomerReturn> list) {
		if (list.isEmpty()) {
			return Map.of();
		}
		return this.returns.findLines(list.stream().map(CustomerReturn::getId).toList()).stream()
			.collect(Collectors.groupingBy(CustomerReturnLine::getReturnId));
	}

	private String nextNumber() {
		return "RET-" + LocalDate.now(this.clock).getYear() + "-"
				+ String.format(Locale.ROOT, "%06d", this.returns.nextNumber());
	}

	static CustomerReturnResponse toResponse(CustomerReturn r, List<CustomerReturnLine> lines) {
		return new CustomerReturnResponse(r.getId(), r.getNumber(), r.getSaleId(), r.getWarehouseId(), r.getCustomerId(),
				r.getSettlement(), r.getRefundMethod(), r.getTotalAmount(), r.getReason(), r.getCreatedAt(),
				r.getCreatedBy(),
				lines.stream()
					.map(l -> new CustomerReturnResponse.Line(l.getId(), l.getLineNumber(), l.getSaleLineId(),
							l.getProductId(), l.getProductSku(), l.getProductName(), l.getQuantity(), l.getAmount(),
							l.getCondition(), l.getStockMovementId()))
					.toList());
	}

	private record Planned(SaleSnapshot.Line sold, int quantity, BigDecimal amount, ItemCondition condition) {
	}

}
