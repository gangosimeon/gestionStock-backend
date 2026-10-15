package com.geststock.sale;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import org.springframework.beans.factory.ObjectProvider;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.geststock.audit.AuditService;
import com.geststock.cash.CashService;
import com.geststock.common.api.PageQuery;
import com.geststock.common.api.SortWhitelist;
import com.geststock.common.security.ActorIdentity;
import com.geststock.customer.CustomerAccountEntryType;
import com.geststock.customer.CustomerAccountService;
import com.geststock.customer.CustomerService;
import com.geststock.customer.CustomerSnapshot;
import com.geststock.customer.PriceCategory;
import com.geststock.exception.BusinessRuleException;
import com.geststock.exception.ConflictException;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.InvalidRequestException;
import com.geststock.exception.NotFoundException;
import com.geststock.exception.UnauthorizedException;
import com.geststock.product.ProductService;
import com.geststock.product.ProductSnapshot;
import com.geststock.sale.dto.CancelSaleRequest;
import com.geststock.sale.dto.CreateSaleRequest;
import com.geststock.sale.dto.SaleFilter;
import com.geststock.sale.dto.SaleResponse;
import com.geststock.stock.StockMovement;
import com.geststock.stock.StockMovementCommand;
import com.geststock.stock.StockMovementType;
import com.geststock.stock.StockService;
import com.geststock.stock.StockSourceType;
import com.geststock.warehouse.WarehouseAccess;

/**
 * Ventes (API publique du module {@code sale}).
 *
 * <p><b>Le client n'impose jamais un prix.</b> Une vente s'enregistre dans <b>une seule
 * transaction</b> :
 * <ol>
 * <li>produits relus en base ; chacun doit exister et être actif ;</li>
 * <li>prix unitaire = tarif du produit (détail ou gros) ; remise ≤ plafond configuré ;
 * coût = prix d'achat de référence ; montants calculés par {@link SalePricing} ;</li>
 * <li>règlement : 0 ≤ payé ≤ total ; un reste dû exige un client identifié (RG-SAL-3) ;</li>
 * <li>vente (numéro {@code VTE-AAAA-nnnnnn}, utilisateur, dépôt) puis <b>sorties de stock</b>
 * par {@link StockService} : disponibilité vérifiée sous verrou, pas de survente
 * ({@code 422 STOCK_INSUFFICIENT}) ;</li>
 * <li>lignes liées à leur sortie de stock ;</li>
 * <li>reste dû porté au compte du client, plafond de crédit contrôlé sous verrou
 * (RG-SAL-4) ;</li>
 * <li>montant payé encaissé dans la caisse ouverte du vendeur, dans le dépôt de la vente
 * ({@link CashService}) ;</li>
 * <li>trace dans le journal d'audit.</li>
 * </ol>
 * Au moindre refus, rien n'est écrit. La base vérifie en outre au COMMIT que chaque ligne a
 * sa sortie de stock, que les totaux sont ceux des lignes et qu'un crédit a sa créance.
 *
 * <p>Une vente payée exige une caisse ouverte : vérifié avant toute écriture.
 */
@Service
@Transactional(readOnly = true)
public class SaleService {

	static final SortWhitelist SORTS = new SortWhitelist(Map.of(
			"createdAt", "createdAt",
			"number", "number",
			"total", "total",
			"customerName", "customerName"), Sort.by(Sort.Direction.DESC, "createdAt"));

	/** Plafond de {@code numeric(14,2)}. */
	static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999.99");

	private final SaleRepository sales;

	private final ProductService products;

	private final CustomerService customers;

	private final CustomerAccountService accounts;

	private final StockService stock;

	private final WarehouseAccess warehouseAccess;

	private final AuditService audit;

	private final CashService cash;

	private final SalesProperties properties;

	private final ObjectProvider<SaleReturnsChecker> returns;

	private final EntityManager entityManager;

	private final Clock clock;

	SaleService(SaleRepository sales, ProductService products, CustomerService customers,
			CustomerAccountService accounts, StockService stock, WarehouseAccess warehouseAccess, AuditService audit,
			CashService cash, SalesProperties properties, ObjectProvider<SaleReturnsChecker> returns,
			EntityManager entityManager, Clock clock) {
		this.sales = sales;
		this.products = products;
		this.customers = customers;
		this.accounts = accounts;
		this.stock = stock;
		this.warehouseAccess = warehouseAccess;
		this.audit = audit;
		this.cash = cash;
		this.properties = properties;
		this.returns = returns;
		this.entityManager = entityManager;
		this.clock = clock;
	}

	/**
	 * Ventes d'un dépôt ({@code warehouseId}), ou de tous si {@code null} (l'appelant a vérifié
	 * {@code warehouse:all}).
	 */
	public Page<SaleResponse> search(SaleFilter filter, UUID warehouseId, PageQuery query) {
		Specification<Sale> spec = Specification.unrestricted();
		if (warehouseId != null) {
			spec = spec.and((root, q, cb) -> cb.equal(root.get("warehouseId"), warehouseId));
		}
		if (filter.status() != null) {
			spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), filter.status()));
		}
		if (filter.customerId() != null) {
			spec = spec.and((root, q, cb) -> cb.equal(root.get("customerId"), filter.customerId()));
		}
		if (filter.paymentMethod() != null) {
			spec = spec.and((root, q, cb) -> cb.equal(root.get("paymentMethod"), filter.paymentMethod()));
		}
		if (filter.from() != null) {
			spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), filter.from()));
		}
		if (filter.to() != null) {
			spec = spec.and((root, q, cb) -> cb.lessThan(root.get("createdAt"), filter.to()));
		}
		if (StringUtils.hasText(filter.q())) {
			String text = filter.q().trim().toLowerCase(Locale.ROOT);
			String pattern = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
			spec = spec.and((root, q, cb) -> cb.or(
					cb.like(cb.lower(root.get("number")), pattern, '\\'),
					cb.like(cb.lower(root.get("customerName")), pattern, '\\')));
		}
		return this.sales.findAll(spec, query.toPageable(SORTS)).map(SaleMapper::toResponse);
	}

	/**
	 * Ventes d'un client dans tous les dépôts que l'utilisateur peut consulter (tous avec
	 * {@code warehouse:all}) : la dette du client est globale, mais les ventes d'un magasin restent
	 * invisibles à qui n'y a pas accès.
	 */
	public Page<SaleResponse> customerSales(UUID customerId, String text, PageQuery query) {
		this.customers.getForAssignment(customerId, false);
		java.util.Set<UUID> visible = this.warehouseAccess.visibleWarehouseIds();
		Specification<Sale> spec = (root, q, cb) -> cb.equal(root.get("customerId"), customerId);
		if (com.geststock.common.api.SearchText.present(text)) {
			String pattern = com.geststock.common.api.SearchText.pattern(text);
			spec = spec.and((root, q, cb) -> cb.like(cb.lower(root.get("number")), pattern, '\\'));
		}
		if (visible != null) {
			spec = spec.and((root, q, cb) -> visible.isEmpty() ? cb.disjunction() : root.get("warehouseId").in(visible));
		}
		return this.sales.findAll(spec, query.toPageable(SORTS)).map(SaleMapper::toResponse);
	}

	public SaleResponse get(UUID id) {
		Sale sale = this.sales.findById(id).orElseThrow(SaleService::notFound);
		requireVisible(sale);
		return SaleMapper.toResponse(sale);
	}

	@Transactional
	public SaleResponse create(CreateSaleRequest request, UUID currentWarehouseId) {
		UUID actor = ActorIdentity.currentUserId()
			.orElseThrow(() -> new UnauthorizedException(ErrorCode.UNAUTHENTICATED));
		// Le dépôt de vente est le dépôt courant ; un warehouseId envoyé doit le confirmer.
		UUID warehouseId = this.warehouseAccess.requireCurrentIfPresent(request.warehouseId(), currentWarehouseId);

		Set<UUID> productIds = new LinkedHashSet<>();
		for (CreateSaleRequest.Item item : request.items()) {
			if (!productIds.add(item.productId())) {
				throw new InvalidRequestException(ErrorCode.SALE_DUPLICATE_PRODUCT,
						ErrorCode.SALE_DUPLICATE_PRODUCT.defaultMessage());
			}
		}
		// Client existant, client saisi à la caisse (créé ou retrouvé, dans cette transaction) ou comptoir.
		CustomerSnapshot customer = request.customerId() != null
				? this.customers.getForAssignment(request.customerId(), true)
				: request.newCustomer() != null
						? this.customers.findOrCreateForSale(request.newCustomer().name().trim(),
								request.newCustomer().phone())
						: null;
		PriceCategory priceType = request.type() != null ? request.type()
				: customer != null ? customer.priceCategory() : PriceCategory.RETAIL;

		// 1 à 3 : produits relus, état, prix serveur, coût, montants.
		Map<UUID, ProductSnapshot> catalog = this.products.snapshots(productIds);
		List<PlannedLine> plan = new ArrayList<>();
		BigDecimal subtotal = BigDecimal.ZERO.setScale(2);
		BigDecimal discountTotal = BigDecimal.ZERO.setScale(2);
		BigDecimal costTotal = BigDecimal.ZERO.setScale(2);
		for (CreateSaleRequest.Item item : request.items()) {
			ProductSnapshot product = sellable(catalog.get(item.productId()));
			BigDecimal unitPrice = SalePricing.unitPrice(product, priceType);
			if (unitPrice.signum() <= 0) {
				throw new BusinessRuleException(ErrorCode.PRODUCT_PRICE_NOT_SET,
						"Le produit " + product.sku() + " n'a pas de prix de vente " + label(priceType) + ".");
			}
			BigDecimal discount = allowedDiscount(item.discountPercent());
			SalePricing.LineAmounts amounts = SalePricing.line(unitPrice, item.quantity(), discount,
					product.purchasePrice());
			plan.add(new PlannedLine(product, item.quantity(), unitPrice, discount, amounts));
			subtotal = subtotal.add(amounts.gross());
			discountTotal = discountTotal.add(amounts.discount());
			costTotal = costTotal.add(amounts.costTotal());
		}
		BigDecimal total = subtotal.subtract(discountTotal);
		if (subtotal.compareTo(MAX_AMOUNT) > 0) {
			throw new InvalidRequestException(ErrorCode.SALE_AMOUNT_OUT_OF_RANGE,
					ErrorCode.SALE_AMOUNT_OUT_OF_RANGE.defaultMessage());
		}

		// Règlement et crédit (RG-SAL-2, RG-SAL-3).
		BigDecimal paid = request.paidAmount() != null ? request.paidAmount().setScale(2) : total;
		if (paid.compareTo(total) > 0) {
			throw new InvalidRequestException(ErrorCode.PAID_AMOUNT_EXCEEDS_TOTAL,
					ErrorCode.PAID_AMOUNT_EXCEEDS_TOTAL.defaultMessage());
		}
		if (paid.signum() > 0 && request.paymentMethod() == null) {
			throw new InvalidRequestException(ErrorCode.PAYMENT_METHOD_REQUIRED,
					ErrorCode.PAYMENT_METHOD_REQUIRED.defaultMessage());
		}
		BigDecimal credit = total.subtract(paid);
		if (credit.signum() > 0 && customer == null) {
			throw new BusinessRuleException(ErrorCode.CUSTOMER_REQUIRED_FOR_CREDIT,
					ErrorCode.CUSTOMER_REQUIRED_FOR_CREDIT.defaultMessage());
		}
		if (paid.signum() > 0) {
			this.cash.requireOpenSession(warehouseId);
		}

		// 4 : vente, au nom de l'utilisateur connecté (created_by).
		Sale sale = this.sales.save(new Sale(nextNumber(), warehouseId, customer != null ? customer.id() : null,
				customer != null ? customer.name() : null, priceType, subtotal, discountTotal, costTotal, paid,
				paid.signum() > 0 ? request.paymentMethod() : null, blankToNull(request.note())));
		this.entityManager.flush();

		// 5 : sorties de stock, sous verrou ; refus = rollback de toute la vente.
		String reason = "Vente " + sale.getNumber();
		List<StockMovementCommand> commands = new ArrayList<>(plan.size());
		for (PlannedLine line : plan) {
			commands.add(StockMovementCommand
				.of(warehouseId, line.product().id(), StockMovementType.SALE, -line.quantity(), StockSourceType.SALE,
						reason)
				.withSource(sale.getId(), sale.getNumber())
				.withUnitCost(line.amounts().unitCost()));
		}
		List<StockMovement> movements = this.stock.applyMovements(commands);

		// 6 : lignes, chacune liée à sa sortie de stock.
		for (int i = 0; i < plan.size(); i++) {
			PlannedLine line = plan.get(i);
			SaleLine saleLine = new SaleLine(sale, i + 1, line.product().id(), line.product().sku(),
					line.product().name(), line.quantity(), line.unitPrice(), line.discountPercent(), line.amounts(),
					movements.get(i).getId());
			this.entityManager.persist(saleLine);
			sale.attach(saleLine);
		}

		// 7 : reste dû au compte du client (plafond contrôlé sous verrou).
		if (credit.signum() > 0) {
			this.accounts.recordCreditSale(customer.id(), credit, sale.getId(), sale.getNumber(), warehouseId);
		}

		// 8 : montant payé encaissé dans la caisse du vendeur.
		if (paid.signum() > 0) {
			this.cash.recordSaleReceipt(warehouseId, sale.getId(), sale.getNumber(), paid, sale.getPaymentMethod(),
					sale.getCustomerId());
		}

		this.entityManager.flush();
		SaleResponse response = SaleMapper.toResponse(sale);
		this.audit.success("CREATE", "SALE", sale.getId().toString(), sale.getNumber(),
				"Vente " + sale.getNumber() + " : total " + total.toPlainString() + ", payé " + paid.toPlainString()
						+ (credit.signum() > 0 ? ", crédit " + credit.toPlainString() : ""),
				response, warehouseId);
		return response;
	}

	/**
	 * Annule une vente : remise en stock de chaque ligne ({@code SALE_CANCELLATION}, au coût de
	 * la vente), annulation de la créance et remboursement du montant payé depuis la caisse
	 * ouverte de l'utilisateur (même dépôt ; espèces suffisantes), dans une transaction. Depuis
	 * le dépôt de la vente seulement.
	 *
	 * @throws ConflictException {@code SALE_NOT_CANCELLABLE}, {@code SALE_CREDIT_ALREADY_SETTLED},
	 *                           {@code WAREHOUSE_MISMATCH}
	 */
	@Transactional
	public SaleResponse cancel(UUID id, CancelSaleRequest request, UUID currentWarehouseId) {
		UUID actor = ActorIdentity.currentUserId()
			.orElseThrow(() -> new UnauthorizedException(ErrorCode.UNAUTHENTICATED));
		Sale sale = this.sales.findForUpdate(id).orElseThrow(SaleService::notFound);
		requireVisible(sale);
		this.warehouseAccess.requireCurrent(sale.getWarehouseId(), currentWarehouseId);
		if (sale.getStatus() != SaleStatus.COMPLETED) {
			throw new ConflictException(ErrorCode.SALE_NOT_CANCELLABLE, ErrorCode.SALE_NOT_CANCELLABLE.defaultMessage());
		}
		SaleReturnsChecker checker = this.returns.getIfAvailable();
		if (checker != null && checker.hasReturns(sale.getId())) {
			throw new ConflictException(ErrorCode.SALE_HAS_RETURNS, ErrorCode.SALE_HAS_RETURNS.defaultMessage());
		}
		this.warehouseAccess.requireAccessible(sale.getWarehouseId());
		if (sale.getPaidAmount().signum() > 0) {
			this.cash.requireOpenSession(sale.getWarehouseId());
		}
		String reason = request.reason().trim();

		String note = "Annulation de la vente " + sale.getNumber() + " : " + reason;
		List<StockMovementCommand> commands = new ArrayList<>();
		for (SaleLine line : sale.getLines()) {
			commands.add(StockMovementCommand
				.of(sale.getWarehouseId(), line.getProductId(), StockMovementType.SALE_CANCELLATION, line.getQuantity(),
						StockSourceType.SALE, note.length() > 500 ? note.substring(0, 500) : note)
				.withSource(sale.getId(), sale.getNumber())
				.withUnitCost(line.getUnitCost()));
		}
		this.stock.applyMovements(commands);

		if (sale.getCreditAmount().signum() > 0) {
			try {
				this.accounts.recordCredit(sale.getCustomerId(), CustomerAccountEntryType.SALE_CANCELLATION,
						sale.getCreditAmount(), sale.getId(), sale.getNumber(), reason, sale.getWarehouseId());
			}
			catch (BusinessRuleException ex) {
				if (ex.getCode() != ErrorCode.AMOUNT_EXCEEDS_CUSTOMER_BALANCE) {
					throw ex;
				}
				throw new ConflictException(ErrorCode.SALE_CREDIT_ALREADY_SETTLED,
						ErrorCode.SALE_CREDIT_ALREADY_SETTLED.defaultMessage());
			}
		}

		if (sale.getPaidAmount().signum() > 0) {
			this.cash.recordSaleRefund(sale.getWarehouseId(), sale.getId(), sale.getNumber(), sale.getPaidAmount(),
					sale.getPaymentMethod(), sale.getCustomerId(), reason);
		}

		sale.cancel(Instant.now(this.clock), actor, reason);
		this.sales.saveAndFlush(sale);
		SaleResponse response = SaleMapper.toResponse(sale);
		this.audit.success("CANCEL", "SALE", sale.getId().toString(), sale.getNumber(),
				"Annulation de la vente " + sale.getNumber() + " : " + reason, response, sale.getWarehouseId());
		return response;
	}

	/**
	 * Vente à retourner (module retours) : verrouillée jusqu'à la fin de la transaction de
	 * l'appelant (deux retours de la même vente se sérialisent), visible, non annulée, dans un
	 * dépôt accessible qui est le dépôt courant.
	 *
	 * @throws ConflictException {@code SALE_NOT_RETURNABLE} si la vente est annulée,
	 *                           {@code WAREHOUSE_MISMATCH} si elle est d'un autre dépôt
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public SaleSnapshot lockForReturn(UUID saleId, UUID currentWarehouseId) {
		Sale sale = this.sales.findForUpdate(saleId).orElseThrow(SaleService::notFound);
		requireVisible(sale);
		this.warehouseAccess.requireCurrent(sale.getWarehouseId(), currentWarehouseId);
		this.entityManager.refresh(sale, LockModeType.PESSIMISTIC_WRITE);
		if (sale.getStatus() != SaleStatus.COMPLETED) {
			throw new ConflictException(ErrorCode.SALE_NOT_RETURNABLE, ErrorCode.SALE_NOT_RETURNABLE.defaultMessage());
		}
		this.warehouseAccess.requireAccessible(sale.getWarehouseId());
		return SaleSnapshot.of(sale);
	}

	// --- Outils -------------------------------------------------------------------------

	private static ProductSnapshot sellable(ProductSnapshot product) {
		if (product == null) {
			throw new NotFoundException(ErrorCode.PRODUCT_NOT_FOUND, ErrorCode.PRODUCT_NOT_FOUND.defaultMessage());
		}
		if (!product.active()) {
			throw new BusinessRuleException(ErrorCode.PRODUCT_INACTIVE,
					"Le produit " + product.sku() + " est désactivé : il ne peut plus être vendu.");
		}
		return product;
	}

	private BigDecimal allowedDiscount(BigDecimal requested) {
		BigDecimal discount = requested != null ? requested : BigDecimal.ZERO;
		BigDecimal max = this.properties.maxDiscountPercent();
		if (discount.compareTo(max) > 0) {
			throw new BusinessRuleException(ErrorCode.DISCOUNT_NOT_ALLOWED,
					"Remise de " + discount.stripTrailingZeros().toPlainString() + " % refusée : maximum autorisé "
							+ max.stripTrailingZeros().toPlainString() + " %.",
					Map.of("maxDiscountPercent", max));
		}
		return discount.setScale(2);
	}

	/** Une vente d'un dépôt hors périmètre est « introuvable » (pas de fuite d'existence). */
	private void requireVisible(Sale sale) {
		if (!this.warehouseAccess.canSee(sale.getWarehouseId())) {
			throw notFound();
		}
	}

	private String nextNumber() {
		return "VTE-" + LocalDate.now(this.clock).getYear() + "-"
				+ String.format(Locale.ROOT, "%06d", this.sales.nextNumber());
	}

	private static String label(PriceCategory priceType) {
		return priceType == PriceCategory.WHOLESALE ? "de gros" : "au détail";
	}

	private static String blankToNull(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

	private static NotFoundException notFound() {
		return new NotFoundException(ErrorCode.SALE_NOT_FOUND, ErrorCode.SALE_NOT_FOUND.defaultMessage());
	}

	private record PlannedLine(ProductSnapshot product, int quantity, BigDecimal unitPrice, BigDecimal discountPercent,
			SalePricing.LineAmounts amounts) {
	}

}
