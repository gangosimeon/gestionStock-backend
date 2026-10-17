package com.geststock.quote;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.geststock.audit.AuditService;
import com.geststock.common.api.PageQuery;
import com.geststock.common.api.SortWhitelist;
import com.geststock.customer.CustomerService;
import com.geststock.customer.CustomerSnapshot;
import com.geststock.exception.BusinessRuleException;
import com.geststock.exception.ConflictException;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.InvalidRequestException;
import com.geststock.exception.NotFoundException;
import com.geststock.product.ProductService;
import com.geststock.product.ProductSnapshot;
import com.geststock.quote.dto.QuoteFilter;
import com.geststock.quote.dto.QuoteReference;
import com.geststock.quote.dto.QuoteRequest;
import com.geststock.quote.dto.QuoteResponse;
import com.geststock.sale.SaleService;
import com.geststock.sale.SaleStatus;
import com.geststock.sale.dto.SaleResponse;
import com.geststock.warehouse.WarehouseAccess;

/**
 * Devis (API publique du module {@code quote}).
 *
 * <p>Un devis ne produit <b>aucun</b> mouvement de stock, encaissement ni créance : c'est une
 * proposition. Ses montants sont calculés ici (quantité × prix unitaire retenu), jamais repris du
 * navigateur. Il appartient au dépôt où il a été créé : un devis d'un dépôt hors périmètre est
 * « introuvable ».
 *
 * <p>Cycle : brouillon → émis → accepté / refusé ; brouillon, émis ou accepté → annulé ; émis ou
 * accepté → facturé. Modifiable tant qu'il est brouillon ou émis. « Expiré » est un état
 * affiché (émis, validité dépassée), jamais enregistré.
 *
 * <p>Facturation : la facture est une vente enregistrée à la caisse par l'utilisateur (stock,
 * paiement ou crédit selon les règles des ventes). {@link #invoice} relie ensuite cette vente au
 * devis, une seule fois, après vérification (même client, même dépôt, vente postérieure au devis
 * et non annulée).
 */
@Service
@Transactional(readOnly = true)
public class QuoteService {

	static final SortWhitelist SORTS = new SortWhitelist(Map.of(
			"quoteDate", "quoteDate",
			"number", "number",
			"total", "total",
			"customerName", "customerName"),
			Sort.by(Sort.Order.desc("quoteDate"), Sort.Order.desc("number")));

	/** Plafond de {@code numeric(14,2)}. */
	static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999.99");

	private static final Set<QuoteStatus> EDITABLE = EnumSet.of(QuoteStatus.DRAFT, QuoteStatus.ISSUED);

	private static final Set<QuoteStatus> INVOICEABLE = EnumSet.of(QuoteStatus.ISSUED, QuoteStatus.ACCEPTED);

	private static final Set<QuoteStatus> CANCELLABLE = EnumSet.of(QuoteStatus.DRAFT, QuoteStatus.ISSUED,
			QuoteStatus.ACCEPTED);

	private final QuoteRepository quotes;

	private final CustomerService customers;

	private final ProductService products;

	private final SaleService sales;

	private final WarehouseAccess warehouseAccess;

	private final AuditService audit;

	private final EntityManager entityManager;

	private final Clock clock;

	private final ZoneId zone;

	QuoteService(QuoteRepository quotes, CustomerService customers, ProductService products, SaleService sales,
			WarehouseAccess warehouseAccess, AuditService audit, EntityManager entityManager, Clock clock,
			@Value("${geststock.dashboard.time-zone:Africa/Abidjan}") String zone) {
		this.quotes = quotes;
		this.customers = customers;
		this.products = products;
		this.sales = sales;
		this.warehouseAccess = warehouseAccess;
		this.audit = audit;
		this.entityManager = entityManager;
		this.clock = clock;
		this.zone = ZoneId.of(zone);
	}

	// --- Lecture ------------------------------------------------------------------------------------

	/**
	 * Devis d'un dépôt ({@code warehouseId}), ou de tous si {@code null} (l'appelant a vérifié
	 * {@code warehouse:all}).
	 */
	public Page<QuoteResponse> search(QuoteFilter filter, UUID warehouseId, PageQuery query) {
		LocalDate today = today();
		Specification<Quote> spec = Specification.unrestricted();
		if (warehouseId != null) {
			spec = spec.and((root, q, cb) -> cb.equal(root.get("warehouseId"), warehouseId));
		}
		if (filter.customerId() != null) {
			spec = spec.and((root, q, cb) -> cb.equal(root.get("customerId"), filter.customerId()));
		}
		if (filter.from() != null) {
			spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("quoteDate"), filter.from()));
		}
		if (filter.to() != null) {
			spec = spec.and((root, q, cb) -> cb.lessThanOrEqualTo(root.get("quoteDate"), filter.to()));
		}
		if (filter.status() != null) {
			spec = spec.and(statusIs(filter.status(), today));
		}
		if (StringUtils.hasText(filter.q())) {
			String text = filter.q().trim().toLowerCase(Locale.ROOT);
			String pattern = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
			spec = spec.and((root, q, cb) -> cb.or(
					cb.like(cb.lower(root.get("number")), pattern, '\\'),
					cb.like(cb.lower(root.get("customerName")), pattern, '\\'),
					cb.like(cb.lower(cb.coalesce(root.get("subject"), "")), pattern, '\\')));
		}
		return this.quotes.findAll(spec, query.toPageable(SORTS)).map((quote) -> toResponse(quote, today));
	}

	public QuoteResponse get(UUID id) {
		Quote quote = this.quotes.findById(id).orElseThrow(QuoteService::notFound);
		requireVisible(quote);
		return toResponse(quote, today());
	}

	/** Devis dont la vente est la facture (informations reprises sur la facture), s'il y en a un. */
	public Optional<QuoteReference> forSale(UUID saleId) {
		return this.quotes.findBySaleId(saleId)
			.filter((quote) -> this.warehouseAccess.canSee(quote.getWarehouseId()))
			.map(QuoteService::toReference);
	}

	// --- Écriture -----------------------------------------------------------------------------------

	/** Nouveau devis (brouillon) dans le dépôt courant. */
	@Transactional
	public QuoteResponse create(QuoteRequest request, UUID currentWarehouseId) {
		Quote quote = new Quote(nextNumber(), currentWarehouseId);
		apply(quote, request);
		this.quotes.save(quote);
		this.entityManager.flush();
		QuoteResponse response = toResponse(quote, today());
		this.audit.success("CREATE", "QUOTE", quote.getId().toString(), quote.getNumber(),
				"Devis " + quote.getNumber() + " pour " + quote.getCustomerName() + " : total "
						+ quote.getTotal().toPlainString(),
				response, quote.getWarehouseId());
		return response;
	}

	/** Modification d'un devis brouillon ou émis (lignes remplacées, total recalculé). */
	@Transactional
	public QuoteResponse update(UUID id, QuoteRequest request) {
		Quote quote = loadForUpdate(id);
		if (!EDITABLE.contains(quote.getStatus())) {
			throw new ConflictException(ErrorCode.QUOTE_NOT_EDITABLE, ErrorCode.QUOTE_NOT_EDITABLE.defaultMessage());
		}
		QuoteResponse before = toResponse(quote, today());
		quote.clearLines();
		// Anciennes lignes supprimées avant d'insérer les nouvelles (numéros de ligne uniques).
		this.entityManager.flush();
		apply(quote, request);
		this.entityManager.flush();
		QuoteResponse after = toResponse(quote, today());
		this.audit.record(com.geststock.audit.AuditEvent.of("UPDATE", "QUOTE", quote.getId(), quote.getNumber())
			.details("Devis " + quote.getNumber() + " modifié : total " + after.total().toPlainString())
			.before(before)
			.after(after)
			.warehouse(quote.getWarehouseId()));
		return after;
	}

	/** Brouillon → émis (remis au client). */
	@Transactional
	public QuoteResponse issue(UUID id) {
		return transition(id, "ISSUE", EnumSet.of(QuoteStatus.DRAFT), QuoteStatus.ISSUED, "émis");
	}

	/** Émis → accepté par le client (y compris après la date de validité : décision de l'utilisateur). */
	@Transactional
	public QuoteResponse accept(UUID id) {
		return transition(id, "ACCEPT", EnumSet.of(QuoteStatus.ISSUED), QuoteStatus.ACCEPTED, "accepté");
	}

	/** Émis → refusé par le client. */
	@Transactional
	public QuoteResponse refuse(UUID id) {
		return transition(id, "REFUSE", EnumSet.of(QuoteStatus.ISSUED), QuoteStatus.REFUSED, "refusé");
	}

	@Transactional
	public QuoteResponse cancel(UUID id, String reason) {
		Quote quote = loadForUpdate(id);
		if (!CANCELLABLE.contains(quote.getStatus())) {
			throw invalidTransition(quote);
		}
		quote.cancel(this.clock.instant(), StringUtils.hasText(reason) ? reason.trim() : null);
		this.entityManager.flush();
		QuoteResponse response = toResponse(quote, today());
		this.audit.success("CANCEL", "QUOTE", quote.getId().toString(), quote.getNumber(),
				"Devis " + quote.getNumber() + " annulé" + (StringUtils.hasText(reason) ? " : " + reason.trim() : ""),
				response, quote.getWarehouseId());
		return response;
	}

	/**
	 * Copie en brouillon dans le dépôt courant, datée d'aujourd'hui : mêmes client, informations,
	 * produits, quantités et prix ; libellés relus dans le catalogue.
	 */
	@Transactional
	public QuoteResponse duplicate(UUID id, UUID currentWarehouseId) {
		Quote source = this.quotes.findById(id).orElseThrow(QuoteService::notFound);
		requireVisible(source);
		QuoteRequest copy = new QuoteRequest(source.getCustomerId(), today(), null, source.getSubject(),
				source.getLotReference(), source.getBeneficiaries(), source.getDeliveryAddress(),
				source.getContractReference(), source.getDeliveryTerms(), source.getPaymentTerms(),
				source.getExecutionDelay(), source.getNotes(),
				source.getLines().stream()
					.map((l) -> new QuoteRequest.Line(l.getProductId(), l.getQuantity(), l.getQuantityPerBeneficiary(),
							l.getUnitPrice()))
					.toList());
		return create(copy, currentWarehouseId);
	}

	/**
	 * Relie au devis la vente (facture) enregistrée à partir de lui, une seule fois : le devis est
	 * verrouillé, et une vente ne peut facturer qu'un devis (index unique). Aucune écriture de
	 * stock, de caisse ni de compte client ici : la vente les a déjà faites.
	 *
	 * @throws ConflictException     {@code QUOTE_ALREADY_INVOICED}, {@code QUOTE_INVALID_TRANSITION},
	 *                               {@code SALE_ALREADY_LINKED_TO_QUOTE}
	 * @throws BusinessRuleException {@code QUOTE_SALE_MISMATCH}
	 */
	@Transactional
	public QuoteResponse invoice(UUID id, UUID saleId) {
		Quote quote = loadForUpdate(id);
		if (quote.getStatus() == QuoteStatus.INVOICED) {
			if (saleId.equals(quote.getSaleId())) {
				// Même demande répétée (double clic, nouvelle tentative) : déjà fait.
				return toResponse(quote, today());
			}
			throw new ConflictException(ErrorCode.QUOTE_ALREADY_INVOICED,
					ErrorCode.QUOTE_ALREADY_INVOICED.defaultMessage());
		}
		if (!INVOICEABLE.contains(quote.getStatus())) {
			throw invalidTransition(quote);
		}
		SaleResponse sale = this.sales.get(saleId);
		if (this.quotes.findBySaleId(saleId).isPresent()) {
			throw new ConflictException(ErrorCode.SALE_ALREADY_LINKED_TO_QUOTE,
					ErrorCode.SALE_ALREADY_LINKED_TO_QUOTE.defaultMessage());
		}
		boolean matches = sale.status() == SaleStatus.COMPLETED
				&& quote.getCustomerId().equals(sale.customerId())
				&& quote.getWarehouseId().equals(sale.warehouseId())
				&& quote.getCreatedAt() != null && !sale.createdAt().isBefore(quote.getCreatedAt());
		if (!matches) {
			throw new BusinessRuleException(ErrorCode.QUOTE_SALE_MISMATCH, ErrorCode.QUOTE_SALE_MISMATCH.defaultMessage());
		}
		quote.invoice(sale.id(), sale.number(), this.clock.instant());
		this.entityManager.flush();
		QuoteResponse response = toResponse(quote, today());
		this.audit.success("INVOICE", "QUOTE", quote.getId().toString(), quote.getNumber(),
				"Devis " + quote.getNumber() + " facturé par la vente " + sale.number() + " (total devis "
						+ quote.getTotal().toPlainString() + ", total vente " + sale.total().toPlainString() + ")",
				response, quote.getWarehouseId());
		return response;
	}

	// --- Outils -------------------------------------------------------------------------------------

	private QuoteResponse transition(UUID id, String action, Set<QuoteStatus> from, QuoteStatus to, String label) {
		Quote quote = loadForUpdate(id);
		if (!from.contains(quote.getStatus())) {
			throw invalidTransition(quote);
		}
		quote.changeStatus(to);
		this.entityManager.flush();
		QuoteResponse response = toResponse(quote, today());
		this.audit.success(action, "QUOTE", quote.getId().toString(), quote.getNumber(),
				"Devis " + quote.getNumber() + " " + label, response, quote.getWarehouseId());
		return response;
	}

	/** Client, informations et lignes : produits relus dans le catalogue, montants calculés ici. */
	private void apply(Quote quote, QuoteRequest request) {
		CustomerSnapshot customer = this.customers.getForAssignment(request.customerId(), true);
		Set<UUID> productIds = new LinkedHashSet<>();
		for (QuoteRequest.Line line : request.lines()) {
			if (!productIds.add(line.productId())) {
				throw new InvalidRequestException(ErrorCode.QUOTE_DUPLICATE_PRODUCT,
						ErrorCode.QUOTE_DUPLICATE_PRODUCT.defaultMessage());
			}
		}
		Map<UUID, ProductSnapshot> catalog = this.products.snapshots(productIds);
		LocalDate date = request.quoteDate() != null ? request.quoteDate() : today();
		if (request.validUntil() != null && request.validUntil().isBefore(date)) {
			throw new InvalidRequestException(ErrorCode.INVALID_PARAMETER,
					"La date de validité ne peut pas précéder la date du devis.");
		}
		quote.describe(customer.id(), customer.name(), date, request.validUntil(), text(request.subject()),
				text(request.lotReference()), request.beneficiaries(), text(request.deliveryAddress()),
				text(request.contractReference()), text(request.deliveryTerms()), text(request.paymentTerms()),
				text(request.executionDelay()), text(request.notes()));
		for (QuoteRequest.Line line : request.lines()) {
			ProductSnapshot product = catalog.get(line.productId());
			if (product == null) {
				throw new NotFoundException(ErrorCode.PRODUCT_NOT_FOUND, ErrorCode.PRODUCT_NOT_FOUND.defaultMessage());
			}
			if (!product.active()) {
				throw new BusinessRuleException(ErrorCode.PRODUCT_INACTIVE,
						"Le produit " + product.sku() + " est désactivé : retirez-le du devis.");
			}
			int quantity = quantityOf(line, request.beneficiaries(), product);
			BigDecimal unitPrice = line.unitPrice().setScale(2, RoundingMode.HALF_UP);
			BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.UNNECESSARY);
			quote.addLine(product.id(), product.sku(), product.name(), quantity, line.quantityPerBeneficiary(), unitPrice,
					lineTotal);
		}
		if (quote.getTotal().compareTo(MAX_AMOUNT) > 0) {
			throw new InvalidRequestException(ErrorCode.QUOTE_AMOUNT_OUT_OF_RANGE,
					ErrorCode.QUOTE_AMOUNT_OUT_OF_RANGE.defaultMessage());
		}
	}

	/**
	 * Quantité totale d'une ligne : quantité par bénéficiaire × nombre de bénéficiaires si elle est
	 * renseignée (calcul serveur), sinon quantité saisie. C'est elle qui est chiffrée et, à la
	 * facturation, sortie du stock par la vente.
	 */
	private static int quantityOf(QuoteRequest.Line line, Integer beneficiaries, ProductSnapshot product) {
		if (line.quantityPerBeneficiary() == null) {
			return line.quantity();
		}
		if (beneficiaries == null || beneficiaries <= 0) {
			throw new InvalidRequestException(ErrorCode.QUOTE_BENEFICIARIES_REQUIRED,
					"Renseignez le nombre de bénéficiaires : la ligne " + product.sku()
							+ " est chiffrée par bénéficiaire.");
		}
		long total = (long) line.quantityPerBeneficiary() * beneficiaries;
		if (total > 100_000) {
			throw new InvalidRequestException(ErrorCode.INVALID_PARAMETER, "Quantité totale de " + product.sku() + " ("
					+ total + ") supérieure à 100 000 : réduisez la quantité par bénéficiaire.");
		}
		return (int) total;
	}

	private static Specification<Quote> statusIs(QuoteDisplayStatus status, LocalDate today) {
		return switch (status) {
			case EXPIRED -> (root, q, cb) -> cb.and(cb.equal(root.get("status"), QuoteStatus.ISSUED),
					cb.lessThan(root.get("validUntil"), today));
			case ISSUED -> (root, q, cb) -> cb.and(cb.equal(root.get("status"), QuoteStatus.ISSUED),
					cb.or(cb.isNull(root.get("validUntil")), cb.greaterThanOrEqualTo(root.get("validUntil"), today)));
			default -> (root, q, cb) -> cb.equal(root.get("status"), QuoteStatus.valueOf(status.name()));
		};
	}

	private Quote loadForUpdate(UUID id) {
		Quote quote = this.quotes.findForUpdate(id).orElseThrow(QuoteService::notFound);
		requireVisible(quote);
		return quote;
	}

	/** Un devis d'un dépôt hors périmètre est « introuvable » (pas de fuite d'existence). */
	private void requireVisible(Quote quote) {
		if (!this.warehouseAccess.canSee(quote.getWarehouseId())) {
			throw notFound();
		}
	}

	private LocalDate today() {
		return LocalDate.now(this.clock.withZone(this.zone));
	}

	private String nextNumber() {
		return "DEV-" + today().getYear() + "-" + String.format(Locale.ROOT, "%06d", this.quotes.nextNumber());
	}

	private static ConflictException invalidTransition(Quote quote) {
		return new ConflictException(ErrorCode.QUOTE_INVALID_TRANSITION,
				"Action impossible : le devis " + quote.getNumber() + " est " + label(quote.getStatus()) + ".");
	}

	private static String label(QuoteStatus status) {
		return switch (status) {
			case DRAFT -> "un brouillon";
			case ISSUED -> "émis";
			case ACCEPTED -> "accepté";
			case REFUSED -> "refusé";
			case INVOICED -> "déjà facturé";
			case CANCELLED -> "annulé";
		};
	}

	private static NotFoundException notFound() {
		return new NotFoundException(ErrorCode.QUOTE_NOT_FOUND, ErrorCode.QUOTE_NOT_FOUND.defaultMessage());
	}

	private static String text(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

	private static QuoteResponse toResponse(Quote quote, LocalDate today) {
		List<QuoteResponse.LineResponse> lines = quote.getLines().stream()
			.map((l) -> new QuoteResponse.LineResponse(l.getId(), l.getLineNumber(), l.getProductId(), l.getProductSku(),
					l.getProductName(), l.getQuantity(), l.getQuantityPerBeneficiary(), l.getUnitPrice(), l.getLineTotal()))
			.toList();
		return new QuoteResponse(quote.getId(), quote.getNumber(), quote.getWarehouseId(), quote.getCustomerId(),
				quote.getCustomerName(), quote.getQuoteDate(), quote.getValidUntil(), quote.getSubject(),
				quote.getLotReference(), quote.getBeneficiaries(), quote.getDeliveryAddress(),
				quote.getContractReference(), quote.getDeliveryTerms(), quote.getPaymentTerms(),
				quote.getExecutionDelay(), quote.getNotes(), quote.getStatus(), quote.displayStatus(today), lines,
				quote.getTotal(), quote.getSaleId(), quote.getSaleNumber(), quote.getInvoicedAt(), quote.getCancelledAt(),
				quote.getCancellationReason(), quote.getCreatedAt(), quote.getUpdatedAt());
	}

	private static QuoteReference toReference(Quote quote) {
		return new QuoteReference(quote.getId(), quote.getNumber(), quote.getQuoteDate(), quote.getSubject(),
				quote.getLotReference(), quote.getBeneficiaries(), quote.getDeliveryAddress(),
				quote.getContractReference(), quote.getDeliveryTerms(), quote.getPaymentTerms(),
				quote.getExecutionDelay(), quote.getNotes());
	}

}
