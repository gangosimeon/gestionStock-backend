package com.geststock.expense;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.geststock.audit.AuditEvent;
import com.geststock.audit.AuditService;
import com.geststock.cash.CashService;
import com.geststock.common.api.PageQuery;
import com.geststock.common.api.SortWhitelist;
import com.geststock.common.security.ActorIdentity;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.InvalidRequestException;
import com.geststock.exception.NotFoundException;
import com.geststock.expense.dto.ExpenseFilter;
import com.geststock.expense.dto.ExpenseRequest;
import com.geststock.expense.dto.ExpenseResponse;
import com.geststock.expense.dto.ExpenseSummaryResponse;
import com.geststock.warehouse.WarehouseAccess;

/**
 * Dépenses (V027, {@code docs/expenses.md}).
 *
 * <p>Une dépense se crée dans le dépôt courant et ne se modifie plus. En espèces, elle sort de la
 * caisse ouverte de son auteur, dans la même transaction (refusée si les espèces manquent). Une
 * erreur se corrige par annulation motivée ; une dépense en espèces annulée revient dans la
 * caisse ouverte de la personne qui annule. La base vérifie au COMMIT le lien avec la caisse.
 */
@Service
@Transactional(readOnly = true)
public class ExpenseService {

	static final SortWhitelist SORTS = new SortWhitelist(
			java.util.Map.of("expenseDate", "expenseDate", "createdAt", "createdAt", "amount", "amount", "number",
					"number", "category", "category"),
			Sort.by(Sort.Direction.DESC, "expenseDate").and(Sort.by(Sort.Direction.DESC, "createdAt")));

	private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

	private final ExpenseRepository repository;

	private final CashService cash;

	private final WarehouseAccess warehouseAccess;

	private final AuditService audit;

	private final EntityManager entityManager;

	private final Clock clock;

	ExpenseService(ExpenseRepository repository, CashService cash, WarehouseAccess warehouseAccess, AuditService audit,
			EntityManager entityManager, Clock clock) {
		this.repository = repository;
		this.cash = cash;
		this.warehouseAccess = warehouseAccess;
		this.audit = audit;
		this.entityManager = entityManager;
		this.clock = clock;
	}

	/**
	 * Dépenses d'un dépôt, ou de tous les dépôts.
	 *
	 * @param warehouseId dépôt, ou {@code null} pour tous (droit vérifié par l'appelant)
	 */
	public Page<ExpenseResponse> list(ExpenseFilter filter, UUID warehouseId, PageQuery query) {
		return this.repository.findAll(matching(filter, warehouseId, true), query.toPageable(SORTS))
			.map(ExpenseService::toResponse);
	}

	/** Totaux des dépenses payées sur les mêmes critères (le statut demandé est ignoré). */
	public ExpenseSummaryResponse summary(ExpenseFilter filter, UUID warehouseId) {
		Specification<Expense> scope = matching(filter, warehouseId, false);
		CriteriaBuilder cb = this.entityManager.getCriteriaBuilder();

		CriteriaQuery<Object[]> byCategory = cb.createQuery(Object[].class);
		Root<Expense> root = byCategory.from(Expense.class);
		Expression<BigDecimal> sum = cb.sum(root.<BigDecimal>get("amount"));
		byCategory.multiselect(root.get("category"), cb.count(root), sum)
			.where(scope.and(status(ExpenseStatus.PAID)).toPredicate(root, byCategory, cb))
			.groupBy(root.get("category"))
			.orderBy(cb.desc(sum), cb.asc(root.get("category")));
		List<ExpenseSummaryResponse.CategoryTotal> categories = new ArrayList<>();
		BigDecimal total = ZERO;
		long count = 0;
		for (Object[] row : this.entityManager.createQuery(byCategory).getResultList()) {
			BigDecimal amount = money(row[2]);
			long n = ((Number) row[1]).longValue();
			categories.add(new ExpenseSummaryResponse.CategoryTotal((ExpenseCategory) row[0], n, amount));
			total = total.add(amount);
			count += n;
		}

		CriteriaQuery<BigDecimal> cashQuery = cb.createQuery(BigDecimal.class);
		Root<Expense> cashRoot = cashQuery.from(Expense.class);
		cashQuery.select(cb.sum(cashRoot.<BigDecimal>get("amount")))
			.where(scope.and(status(ExpenseStatus.PAID))
				.and((r, q, b) -> b.equal(r.get("paymentMethod"), ExpensePaymentMethod.CASH))
				.toPredicate(cashRoot, cashQuery, cb));
		BigDecimal cashTotal = money(this.entityManager.createQuery(cashQuery).getSingleResult());

		long cancelled = this.repository.count(scope.and(status(ExpenseStatus.CANCELLED)));
		return new ExpenseSummaryResponse(total, count, cashTotal, cancelled, categories);
	}

	public ExpenseResponse get(UUID id) {
		return toResponse(loadVisible(id));
	}

	/** Enregistre une dépense dans le dépôt donné ; en espèces, la sort de ma caisse ouverte. */
	@Transactional
	public ExpenseResponse create(ExpenseRequest request, UUID warehouseId) {
		LocalDate today = LocalDate.now(this.clock);
		LocalDate date = request.expenseDate() != null ? request.expenseDate() : today;
		if (date.isAfter(today)) {
			throw new InvalidRequestException(ErrorCode.EXPENSE_DATE_IN_FUTURE,
					ErrorCode.EXPENSE_DATE_IN_FUTURE.defaultMessage());
		}
		ExpensePaymentMethod method = request.paymentMethod() != null ? request.paymentMethod()
				: ExpensePaymentMethod.CASH;
		BigDecimal amount = request.amount().setScale(2);
		if (method == ExpensePaymentMethod.CASH) {
			// Avant toute écriture : caisse ouverte dans ce dépôt et espèces suffisantes (revérifié à la sortie).
			this.cash.requireCashAvailable(warehouseId, amount);
		}

		Expense expense = this.repository.saveAndFlush(new Expense(nextNumber(), warehouseId, request.category(),
				request.label().trim(), amount, date, method, trimToNull(request.externalReference()),
				trimToNull(request.note())));
		if (expense.isPaidInCash()) {
			this.cash.recordExpense(warehouseId, expense.getId(), expense.getNumber(), amount,
					"Dépense " + expense.getNumber() + " : " + expense.getLabel());
		}

		ExpenseResponse created = toResponse(expense);
		this.audit.record(AuditEvent.of("CREATE", "EXPENSE", expense.getId(), expense.getNumber())
			.details("Dépense " + expense.getNumber() + " (" + expense.getCategory() + ") : " + expense.getLabel() + ", "
					+ amount.toPlainString() + " (" + method + ")")
			.warehouse(warehouseId)
			.after(created));
		return created;
	}

	/**
	 * Annule une dépense (motif obligatoire). En espèces, l'argent revient dans ma caisse ouverte
	 * du dépôt de la dépense ; sans caisse ouverte, l'annulation est refusée.
	 */
	@Transactional
	public ExpenseResponse cancel(UUID id, String reason) {
		loadVisible(id);
		Expense expense = this.repository.findForUpdate(id).orElseThrow(ExpenseService::notFound);
		ExpenseResponse before = toResponse(expense);
		String motive = reason.trim();
		expense.cancel(Instant.now(this.clock), ActorIdentity.currentUserId().orElse(null), motive);
		Expense saved = this.repository.saveAndFlush(expense);
		if (saved.isPaidInCash()) {
			this.cash.recordExpenseCancellation(saved.getWarehouseId(), saved.getId(), saved.getNumber(),
					saved.getAmount(), "Annulation de la dépense " + saved.getNumber() + " : " + motive);
		}

		ExpenseResponse cancelled = toResponse(saved);
		this.audit.record(AuditEvent.of("CANCEL", "EXPENSE", saved.getId(), saved.getNumber())
			.details("Annulation de la dépense " + saved.getNumber() + " (" + saved.getAmount().toPlainString() + ") : "
					+ motive)
			.warehouse(saved.getWarehouseId())
			.before(before)
			.after(cancelled));
		return cancelled;
	}

	// --- Outils -----------------------------------------------------------------------------

	/** Dépense d'un dépôt visible ; sinon « introuvable » (on ne révèle pas son existence). */
	private Expense loadVisible(UUID id) {
		Expense expense = this.repository.findById(id).orElseThrow(ExpenseService::notFound);
		if (!this.warehouseAccess.canSee(expense.getWarehouseId())) {
			throw notFound();
		}
		return expense;
	}

	private static Specification<Expense> matching(ExpenseFilter filter, UUID warehouseId, boolean withStatus) {
		Specification<Expense> spec = Specification.unrestricted();
		if (warehouseId != null) {
			spec = spec.and((root, query, cb) -> cb.equal(root.get("warehouseId"), warehouseId));
		}
		if (filter.dateFrom() != null) {
			spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("expenseDate"), filter.dateFrom()));
		}
		if (filter.dateTo() != null) {
			spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("expenseDate"), filter.dateTo()));
		}
		if (filter.category() != null) {
			spec = spec.and((root, query, cb) -> cb.equal(root.get("category"), filter.category()));
		}
		if (filter.paymentMethod() != null) {
			spec = spec.and((root, query, cb) -> cb.equal(root.get("paymentMethod"), filter.paymentMethod()));
		}
		if (withStatus && filter.status() != null) {
			spec = spec.and(status(filter.status()));
		}
		if (StringUtils.hasText(filter.search())) {
			String pattern = "%" + escape(filter.search().trim().toLowerCase(Locale.ROOT)) + "%";
			spec = spec.and((root, query, cb) -> cb.or(
					cb.like(cb.lower(root.get("label")), pattern, '\\'),
					cb.like(cb.lower(root.get("number")), pattern, '\\'),
					cb.like(cb.lower(cb.coalesce(root.get("externalReference"), "")), pattern, '\\'),
					cb.like(cb.lower(cb.coalesce(root.get("note"), "")), pattern, '\\')));
		}
		return spec;
	}

	private static Specification<Expense> status(ExpenseStatus status) {
		return (root, query, cb) -> cb.equal(root.get("status"), status);
	}

	static ExpenseResponse toResponse(Expense expense) {
		return new ExpenseResponse(expense.getId(), expense.getNumber(), expense.getWarehouseId(),
				expense.getCategory(), expense.getLabel(), expense.getAmount(), expense.getExpenseDate(),
				expense.getPaymentMethod(), expense.getExternalReference(), expense.getNote(), expense.getStatus(),
				expense.getCancelledAt(), expense.getCancelledBy(), expense.getCancellationReason(),
				expense.getCreatedAt(), expense.getCreatedBy());
	}

	private String nextNumber() {
		return "DEP-" + LocalDate.now(this.clock).getYear() + "-"
				+ String.format(Locale.ROOT, "%06d", this.repository.nextNumber());
	}

	private static NotFoundException notFound() {
		return new NotFoundException(ErrorCode.EXPENSE_NOT_FOUND, ErrorCode.EXPENSE_NOT_FOUND.defaultMessage());
	}

	private static BigDecimal money(Object value) {
		return value == null ? ZERO : new BigDecimal(value.toString()).setScale(2);
	}

	private static String escape(String text) {
		return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	private static String trimToNull(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

}
