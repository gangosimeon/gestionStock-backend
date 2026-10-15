package com.geststock.cash;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.geststock.audit.AuditService;
import com.geststock.cash.dto.CashEntryResponse;
import com.geststock.cash.dto.CashOperationRequest;
import com.geststock.cash.dto.CashSessionFilter;
import com.geststock.cash.dto.CashSessionResponse;
import com.geststock.cash.dto.CloseCashSessionRequest;
import com.geststock.cash.dto.OpenCashSessionRequest;
import com.geststock.common.api.PageQuery;
import com.geststock.common.api.SortWhitelist;
import com.geststock.common.security.ActorIdentity;
import com.geststock.common.security.ActorPermissions;
import com.geststock.common.security.Permissions;
import com.geststock.exception.BusinessRuleException;
import com.geststock.exception.ConflictException;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.NotFoundException;
import com.geststock.exception.UnauthorizedException;
import com.geststock.warehouse.WarehouseAccess;

/**
 * <b>Caisse : seul point d'écriture des mouvements d'argent.</b>
 *
 * <p>Pas de caisse globale : chaque utilisateur ouvre <b>sa</b> caisse dans un dépôt (au plus
 * une ouverte à la fois) et chaque écriture est attribuée à une session, un utilisateur, une
 * date, une référence ({@code OPC-AAAA-nnnnnn}), un type et un montant. Seul le titulaire
 * écrit dans sa caisse et la clôture (contrôlé aussi par la base).
 *
 * <p>Règles :
 * <ul>
 * <li>ouverture : fond ≥ 0, dans le dépôt courant ; une seule caisse ouverte par
 * utilisateur ;</li>
 * <li>écritures sous verrou de la session ({@code SELECT … FOR UPDATE}) : une sortie
 * d'espèces ne peut pas dépasser les espèces attendues ; une pièce n'est encaissée qu'une
 * fois ; une vente s'encaisse dans une caisse ouverte dans son dépôt ;</li>
 * <li>clôture : espèces comptées ; attendu et écart (compté − attendu) figés.</li>
 * </ul>
 * Les ventes (encaissement, remboursement) et les règlements clients appellent ce service dans
 * leur propre transaction.
 */
@Service
@Transactional(readOnly = true)
public class CashService {

	static final SortWhitelist SESSION_SORTS = new SortWhitelist(Map.of(
			"openedAt", "openedAt",
			"closedAt", "closedAt",
			"number", "number"), Sort.by(Sort.Direction.DESC, "openedAt"));

	static final SortWhitelist ENTRY_SORTS = new SortWhitelist(Map.of("createdAt", "createdAt", "amount", "amount"),
			Sort.by(Sort.Direction.DESC, "createdAt"));

	private final CashSessionRepository sessions;

	private final CashEntryRepository entries;

	private final WarehouseAccess warehouseAccess;

	private final AuditService audit;

	private final EntityManager entityManager;

	private final Clock clock;

	CashService(CashSessionRepository sessions, CashEntryRepository entries, WarehouseAccess warehouseAccess,
			AuditService audit, EntityManager entityManager, Clock clock) {
		this.sessions = sessions;
		this.entries = entries;
		this.warehouseAccess = warehouseAccess;
		this.audit = audit;
		this.entityManager = entityManager;
		this.clock = clock;
	}

	// --- Consultation ------------------------------------------------------------------------

	/**
	 * Caisse ouverte de l'utilisateur courant, s'il en a une, située par rapport au dépôt courant :
	 * ouverte dans un autre dépôt, elle n'y est pas utilisable ({@code inCurrentWarehouse = false}).
	 */
	public Optional<CashSessionResponse> current(UUID currentWarehouseId) {
		return this.sessions.findOpenByCashier(actor()).map(this::toResponse)
			.map(session -> session.locatedIn(currentWarehouseId));
	}

	/**
	 * Historique : ses propres caisses, ou toutes celles du périmètre avec {@code cash:read}.
	 *
	 * @param warehouseId dépôt, ou {@code null} pour tous (l'appelant a vérifié warehouse:all)
	 */
	public Page<CashSessionResponse> history(CashSessionFilter filter, UUID warehouseId, PageQuery query) {
		UUID cashier = supervisor() ? filter.cashierId() : actor();
		Specification<CashSession> spec = Specification.unrestricted();
		if (warehouseId != null) {
			spec = spec.and((root, q, cb) -> cb.equal(root.get("warehouseId"), warehouseId));
		}
		if (cashier != null) {
			spec = spec.and((root, q, cb) -> cb.equal(root.get("cashierId"), cashier));
		}
		if (filter.status() != null) {
			spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), filter.status()));
		}
		if (com.geststock.common.api.SearchText.present(filter.q())) {
			String pattern = com.geststock.common.api.SearchText.pattern(filter.q());
			spec = spec.and((root, q, cb) -> cb.like(cb.lower(root.get("number")), pattern, '\\'));
		}
		if (filter.from() != null) {
			spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("openedAt"), filter.from()));
		}
		if (filter.to() != null) {
			spec = spec.and((root, q, cb) -> cb.lessThan(root.get("openedAt"), filter.to()));
		}
		Page<CashSession> page = this.sessions.findAll(spec, query.toPageable(SESSION_SORTS));
		Map<UUID, CashSummary> summaries = summariesOf(page.getContent());
		return page.map(session -> toResponse(session, summaries.get(session.getId())));
	}

	public CashSessionResponse get(UUID id) {
		return toResponse(loadReadable(id));
	}

	/** Écritures d'une session, des plus récentes aux plus anciennes par défaut. */
	public Page<CashEntryResponse> entries(UUID sessionId, PageQuery query) {
		loadReadable(sessionId);
		return this.entries.findBySession(sessionId, query.toPageable(ENTRY_SORTS)).map(CashService::toResponse);
	}

	/**
	 * Vérifie, sans rien écrire, que l'utilisateur a une caisse ouverte dans ce dépôt : refus clair
	 * avant toute écriture d'une vente ou d'un règlement.
	 */
	public void requireOpenSession(UUID warehouseId) {
		CashSession session = this.sessions.findOpenByCashier(actor()).orElseThrow(CashService::noOpenSession);
		requireWarehouse(session, warehouseId);
	}

	/**
	 * Contrôle préalable d'une sortie d'espèces, sans rien écrire : caisse ouverte et espèces
	 * suffisantes. Permet de refuser avant toute autre écriture ; la sortie elle-même revérifie
	 * sous verrou de la session.
	 */
	public void requireCashAvailable(UUID warehouseId, BigDecimal amount) {
		CashSession session = this.sessions.findOpenByCashier(actor()).orElseThrow(CashService::noOpenSession);
		requireWarehouse(session, warehouseId);
		BigDecimal available = summaryOf(session).expectedCash();
		if (amount.setScale(2).compareTo(available) > 0) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("sessionId", session.getId());
			details.put("available", available);
			details.put("requested", amount.setScale(2));
			throw new BusinessRuleException(ErrorCode.CASH_INSUFFICIENT, ErrorCode.CASH_INSUFFICIENT.defaultMessage(),
					details);
		}
	}

	// --- Cycle de vie ------------------------------------------------------------------------

	/** Ouvre la caisse de l'utilisateur courant dans le dépôt courant. */
	@Transactional
	public CashSessionResponse open(OpenCashSessionRequest request, UUID warehouseId) {
		UUID actor = actor();
		if (this.sessions.findOpenByCashier(actor).isPresent()) {
			throw new ConflictException(ErrorCode.CASH_SESSION_ALREADY_OPEN,
					ErrorCode.CASH_SESSION_ALREADY_OPEN.defaultMessage());
		}
		CashSession session = this.sessions.saveAndFlush(new CashSession(nextSessionNumber(), warehouseId, actor,
				blankToNull(request.terminal()), request.openingBalance().setScale(2), Instant.now(this.clock),
				blankToNull(request.note())));
		CashSessionResponse response = toResponse(session);
		this.audit.success("OPEN", "CASH_SESSION", session.getId().toString(), session.getNumber(),
				"Ouverture de la caisse " + session.getNumber() + ", fond " + session.getOpeningBalance().toPlainString(),
				response, warehouseId);
		return response;
	}

	/**
	 * Clôture la caisse de l'utilisateur courant : espèces comptées, attendu et écart figés. Depuis
	 * le dépôt de la caisse ({@code CASH_SESSION_OTHER_WAREHOUSE} sinon).
	 */
	@Transactional
	public CashSessionResponse close(CloseCashSessionRequest request, UUID currentWarehouseId) {
		UUID actor = actor();
		CashSession session = lockOpenSession(actor);
		requireWarehouse(session, currentWarehouseId);
		CashSummary summary = summaryOf(session);
		session.close(Instant.now(this.clock), actor, request.countedCash().setScale(2), summary.expectedCash(),
				blankToNull(request.note()));
		this.sessions.saveAndFlush(session);
		CashSessionResponse response = toResponse(session, summary);
		this.audit.success("CLOSE", "CASH_SESSION", session.getId().toString(), session.getNumber(),
				"Clôture de la caisse " + session.getNumber() + " : attendu " + session.getExpectedCash().toPlainString()
						+ ", compté " + session.getCountedCash().toPlainString() + ", écart "
						+ session.getDifference().toPlainString(),
				response, session.getWarehouseId());
		return response;
	}

	/**
	 * Apport ({@code IN}) ou sortie ({@code OUT}) manuelle d'espèces dans la caisse de l'utilisateur,
	 * depuis le dépôt de cette caisse.
	 */
	@Transactional
	public CashEntryResponse recordManual(CashOperationRequest request, UUID currentWarehouseId) {
		CashEntryType type = request.type() == CashEntryType.Direction.IN ? CashEntryType.CASH_IN : CashEntryType.CASH_OUT;
		CashEntry entry = post(type, PaymentMethod.CASH, request.amount(), currentWarehouseId, null, null, null, null,
				blankToNull(request.note()), null, null);
		CashEntryResponse response = toResponse(entry);
		UUID warehouseId = currentWarehouseId;
		this.audit.success(type.name(), "CASH_ENTRY", entry.getId().toString(), entry.getNumber(),
				(type == CashEntryType.CASH_IN ? "Apport" : "Sortie") + " d'espèces " + entry.getAmount().toPlainString()
						+ (entry.getNote() != null ? " : " + entry.getNote() : ""),
				response, warehouseId);
		return response;
	}

	// --- Encaissements des autres modules -----------------------------------------------------

	/** Encaissement d'une vente dans la caisse de l'utilisateur, ouverte dans le dépôt de la vente. */
	@Transactional(propagation = Propagation.MANDATORY, readOnly = false)
	public CashEntryResponse recordSaleReceipt(UUID warehouseId, UUID saleId, String saleNumber, BigDecimal amount,
			PaymentMethod method, UUID customerId) {
		return toResponse(post(CashEntryType.SALE, method, amount, warehouseId, customerId, "SALE", saleId, saleNumber,
				null, null, null));
	}

	/** Remboursement d'une vente annulée, depuis la caisse de l'utilisateur dans le dépôt de la vente. */
	@Transactional(propagation = Propagation.MANDATORY, readOnly = false)
	public CashEntryResponse recordSaleRefund(UUID warehouseId, UUID saleId, String saleNumber, BigDecimal amount,
			PaymentMethod method, UUID customerId, String note) {
		return toResponse(post(CashEntryType.SALE_REFUND, method, amount, warehouseId, customerId, "SALE", saleId,
				saleNumber, note, null, null));
	}

	/** Remboursement d'un retour client (avoir), depuis la caisse de l'utilisateur dans le dépôt du retour. */
	@Transactional(propagation = Propagation.MANDATORY, readOnly = false)
	public CashEntryResponse recordReturnRefund(UUID warehouseId, UUID returnId, String returnNumber, BigDecimal amount,
			PaymentMethod method, UUID customerId, String note) {
		return toResponse(post(CashEntryType.RETURN_REFUND, method, amount, warehouseId, customerId, "CUSTOMER_RETURN",
				returnId, returnNumber, note, null, null));
	}

	/**
	 * Paiement d'un fournisseur en espèces : décaissement dans la caisse ouverte de l'utilisateur,
	 * dans le dépôt courant (refusé si les espèces manquent).
	 *
	 * @param warehouseId dépôt exigé pour la caisse (dépôt courant)
	 * @return référence de l'écriture de caisse
	 */
	@Transactional(propagation = Propagation.MANDATORY, readOnly = false)
	public CashEntryResponse recordSupplierPayment(UUID warehouseId, UUID paymentId, String paymentNumber,
			BigDecimal amount, String note) {
		return toResponse(post(CashEntryType.SUPPLIER_PAYMENT, PaymentMethod.CASH, amount, warehouseId, null,
				"SUPPLIER_PAYMENT", paymentId, paymentNumber, note, null, null));
	}

	/**
	 * Dépense payée en espèces : sortie de la caisse ouverte de l'utilisateur, dans le dépôt de
	 * la dépense (refusée si les espèces manquent).
	 */
	@Transactional(propagation = Propagation.MANDATORY, readOnly = false)
	public CashEntryResponse recordExpense(UUID warehouseId, UUID expenseId, String expenseNumber, BigDecimal amount,
			String note) {
		return toResponse(post(CashEntryType.EXPENSE, PaymentMethod.CASH, amount, warehouseId, null, "EXPENSE",
				expenseId, expenseNumber, note, null, null));
	}

	/**
	 * Annulation d'une dépense payée en espèces : l'argent revient dans la caisse ouverte de la
	 * personne qui annule, dans le dépôt de la dépense.
	 */
	@Transactional(propagation = Propagation.MANDATORY, readOnly = false)
	public CashEntryResponse recordExpenseCancellation(UUID warehouseId, UUID expenseId, String expenseNumber,
			BigDecimal amount, String note) {
		return toResponse(post(CashEntryType.EXPENSE_CANCELLATION, PaymentMethod.CASH, amount, warehouseId, null,
				"EXPENSE", expenseId, expenseNumber, note, null, null));
	}

	/**
	 * Corrige un apport ou une sortie manuelle saisi par erreur : écriture inverse du même
	 * montant dans la même caisse (ouverte, du titulaire). L'écriture d'origine reste ; une
	 * écriture ne se corrige qu'une fois. Depuis le dépôt de la caisse.
	 */
	@Transactional
	public CashEntryResponse reverseManual(UUID entryId, String reason, UUID currentWarehouseId) {
		UUID actor = actor();
		CashSession session = lockOpenSession(actor);
		requireWarehouse(session, currentWarehouseId);
		CashEntry original = this.entries.findById(entryId)
			.filter(entry -> entry.getSessionId().equals(session.getId()))
			.orElseThrow(() -> new NotFoundException(ErrorCode.CASH_ENTRY_NOT_FOUND,
					"Opération introuvable dans votre caisse ouverte."));
		CashEntryType correction = switch (original.getType()) {
			case CASH_IN -> CashEntryType.CORRECTION_OUT;
			case CASH_OUT -> CashEntryType.CORRECTION_IN;
			default -> throw new ConflictException(ErrorCode.CASH_ENTRY_NOT_REVERSIBLE,
					ErrorCode.CASH_ENTRY_NOT_REVERSIBLE.defaultMessage());
		};
		if (this.entries.existsByReversalOf(entryId)) {
			throw new ConflictException(ErrorCode.CASH_ENTRY_ALREADY_REVERSED,
					ErrorCode.CASH_ENTRY_ALREADY_REVERSED.defaultMessage());
		}
		String note = "Correction de " + original.getNumber() + " : " + reason.trim();
		CashEntry entry = post(correction, PaymentMethod.CASH, original.getAmount(), currentWarehouseId, null, null, null,
				original.getNumber(), note.length() > 500 ? note.substring(0, 500) : note, null, original.getId());
		CashEntryResponse response = toResponse(entry);
		this.audit.success("CORRECTION", "CASH_ENTRY", entry.getId().toString(), entry.getNumber(),
				"Correction de l'opération " + original.getNumber() + " (" + original.getAmount().toPlainString() + ") : "
						+ reason.trim(),
				response, session.getWarehouseId());
		return response;
	}

	// --- Cœur ------------------------------------------------------------------------------------

	/**
	 * Écrit une écriture dans la caisse ouverte de l'utilisateur, sous verrou de la session.
	 *
	 * @param warehouseId dépôt exigé pour la caisse (obligatoire : jamais d'écriture hors du dépôt)
	 * @param number      référence déjà attribuée, ou {@code null} pour en attribuer une
	 * @param reversalOf  écriture corrigée (corrections uniquement), ou {@code null}
	 */
	CashEntry post(CashEntryType type, PaymentMethod method, BigDecimal amount, UUID warehouseId, UUID customerId,
			String sourceType, UUID sourceId, String sourceReference, String note, String number, UUID reversalOf) {
		UUID actor = actor();
		CashSession session = lockOpenSession(actor);
		requireWarehouse(session, warehouseId);
		if (sourceId != null && this.entries.existsBySource(sourceType, sourceId, type)) {
			throw new ConflictException(ErrorCode.CASH_ENTRY_ALREADY_RECORDED,
					ErrorCode.CASH_ENTRY_ALREADY_RECORDED.defaultMessage());
		}
		BigDecimal value = amount.setScale(2);
		if (type.direction() == CashEntryType.Direction.OUT && method == PaymentMethod.CASH) {
			BigDecimal available = summaryOf(session).expectedCash();
			if (value.compareTo(available) > 0) {
				Map<String, Object> details = new LinkedHashMap<>();
				details.put("sessionId", session.getId());
				details.put("available", available);
				details.put("requested", value);
				throw new BusinessRuleException(ErrorCode.CASH_INSUFFICIENT, ErrorCode.CASH_INSUFFICIENT.defaultMessage(),
						details);
			}
		}
		CashEntry entry = this.entries.save(new CashEntry(number != null ? number : nextEntryNumber(), session.getId(),
				type, method, value, customerId, sourceType, sourceId, sourceReference, note, reversalOf,
				Instant.now(this.clock), actor));
		this.entityManager.flush();
		return entry;
	}

	/** Référence d'une écriture à venir (règlement client : connue avant l'écriture au compte). */
	String nextEntryNumber() {
		return "OPC-" + LocalDate.now(this.clock).getYear() + "-"
				+ String.format(Locale.ROOT, "%06d", this.entries.nextNumber());
	}

	/** Caisse ouverte de l'utilisateur, relue sous verrou : écritures et clôture se sérialisent. */
	private CashSession lockOpenSession(UUID actor) {
		CashSession session = this.sessions.findOpenByCashier(actor).orElseThrow(CashService::noOpenSession);
		this.entityManager.refresh(session, LockModeType.PESSIMISTIC_WRITE);
		if (!session.isOpen()) {
			throw noOpenSession();
		}
		return session;
	}

	/**
	 * Une caisse ne sert que son dépôt : toute écriture, clôture ou vérification indique le dépôt
	 * où elle a lieu ; un oubli est une erreur de programmation, jamais un passe-droit.
	 */
	private void requireWarehouse(CashSession session, UUID warehouseId) {
		Objects.requireNonNull(warehouseId, "dépôt de l'opération de caisse");
		if (!warehouseId.equals(session.getWarehouseId())) {
			throw new ConflictException(ErrorCode.CASH_SESSION_OTHER_WAREHOUSE,
					ErrorCode.CASH_SESSION_OTHER_WAREHOUSE.defaultMessage());
		}
	}

	/** Sa propre caisse, ou toute caisse d'un dépôt visible avec {@code cash:read} ; sinon « introuvable ». */
	private CashSession loadReadable(UUID id) {
		CashSession session = this.sessions.findById(id).orElseThrow(CashService::notFound);
		boolean own = session.getCashierId().equals(actor());
		if (!own && !(supervisor() && this.warehouseAccess.canSee(session.getWarehouseId()))) {
			throw notFound();
		}
		return session;
	}

	private CashSummary summaryOf(CashSession session) {
		return summariesOf(List.of(session)).get(session.getId());
	}

	private Map<UUID, CashSummary> summariesOf(List<CashSession> list) {
		Map<UUID, CashSummary> result = new HashMap<>();
		if (list.isEmpty()) {
			return result;
		}
		Map<UUID, List<Object[]>> rows = new HashMap<>();
		for (Object[] row : this.entries.totalsOf(list.stream().map(CashSession::getId).toList())) {
			rows.computeIfAbsent((UUID) row[0], key -> new ArrayList<>()).add(new Object[] { row[1], row[2], row[3] });
		}
		for (CashSession session : list) {
			result.put(session.getId(),
					CashSummary.of(session.getOpeningBalance(), rows.getOrDefault(session.getId(), List.of())));
		}
		return result;
	}

	private CashSessionResponse toResponse(CashSession session) {
		return toResponse(session, summaryOf(session));
	}

	/** Une caisse clôturée garde l'attendu et l'écart figés à sa clôture. */
	private static CashSessionResponse toResponse(CashSession session, CashSummary summary) {
		boolean open = session.isOpen();
		return new CashSessionResponse(session.getId(), session.getNumber(), session.getStatus(),
				session.getWarehouseId(), session.getTerminal(), session.getOpenedAt(), session.getCashierId(),
				session.getOpeningBalance(), session.getOpeningNote(), session.getClosedAt(), session.getClosedBy(),
				session.getCountedCash(), summary.cashSalesTotal(), summary.totalIn(), summary.totalOut(),
				open ? summary.expectedCash() : session.getExpectedCash(),
				open ? BigDecimal.ZERO.setScale(2) : session.getDifference(), summary.totalsByMethod(),
				session.getClosingNote(), null);
	}

	static CashEntryResponse toResponse(CashEntry entry) {
		return new CashEntryResponse(entry.getId(), entry.getNumber(), entry.getSessionId(), entry.getDirection(),
				entry.getType(), entry.getPaymentMethod(), entry.getAmount(), entry.getCustomerId(), entry.getSourceType(),
				entry.getSourceId(), entry.getSourceReference(), entry.getNote(), entry.getReversalOfId(), entry.getCreatedAt(),
				entry.getCreatedBy());
	}

	private String nextSessionNumber() {
		return "CAI-" + LocalDate.now(this.clock).getYear() + "-"
				+ String.format(Locale.ROOT, "%06d", this.sessions.nextNumber());
	}

	private static UUID actor() {
		return ActorIdentity.currentUserId().orElseThrow(() -> new UnauthorizedException(ErrorCode.UNAUTHENTICATED));
	}

	private static boolean supervisor() {
		return ActorPermissions.current().contains(Permissions.CASH_READ);
	}

	private static String blankToNull(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

	private static ConflictException noOpenSession() {
		return new ConflictException(ErrorCode.NO_OPEN_CASH_SESSION, ErrorCode.NO_OPEN_CASH_SESSION.defaultMessage());
	}

	private static NotFoundException notFound() {
		return new NotFoundException(ErrorCode.CASH_SESSION_NOT_FOUND, ErrorCode.CASH_SESSION_NOT_FOUND.defaultMessage());
	}

}
