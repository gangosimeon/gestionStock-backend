package com.geststock.customer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.geststock.common.api.PageQuery;
import com.geststock.common.api.SortWhitelist;
import com.geststock.common.security.ActorIdentity;
import com.geststock.customer.dto.CustomerAccountEntryResponse;
import com.geststock.customer.dto.CustomerAccountResponse;
import com.geststock.exception.BusinessRuleException;
import com.geststock.exception.ConflictException;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.InvalidRequestException;
import com.geststock.exception.UnauthorizedException;

/**
 * <b>Compte client : seul point d'écriture des créances.</b> Utilisé par les modules ventes
 * (vente à crédit, annulation), paiements (règlements) et retours (avoirs). Ce n'est pas une
 * comptabilité : il ne suit que ce que le client doit au magasin.
 *
 * <p>Chaque écriture s'exécute sous <b>verrou du client</b> ({@code SELECT … FOR UPDATE}),
 * dans la transaction de l'appelant : deux ventes à crédit simultanées ne peuvent pas dépasser
 * ensemble le plafond, deux règlements simultanés ne peuvent pas dépasser la dette.
 *
 * <p>Règles :
 * <ul>
 * <li>débit (vente à crédit) : client actif, solde dû après écriture ≤ plafond de crédit
 * (RG-SAL-4) ;</li>
 * <li>crédit (règlement, avoir, annulation) : montant ≤ solde dû — pas de trop-perçu (l'audit
 * relevait un surpaiement accepté) ; possible sur un client désactivé ;</li>
 * <li>montant > 0, deux décimales au plus ; une même pièce n'est imputée qu'une fois.</li>
 * </ul>
 */
@Service
@Transactional
public class CustomerAccountService {

	static final SortWhitelist SORTS = new SortWhitelist(Map.of("createdAt", "createdAt", "amount", "amount"),
			Sort.by(Sort.Direction.DESC, "createdAt"));

	private final CustomerService customers;

	private final CustomerAccountEntryRepository entries;

	private final EntityManager entityManager;

	private final Clock clock;

	CustomerAccountService(CustomerService customers, CustomerAccountEntryRepository entries,
			EntityManager entityManager, Clock clock) {
		this.customers = customers;
		this.entries = entries;
		this.entityManager = entityManager;
		this.clock = clock;
	}

	/** Plafond, solde dû et crédit disponible. */
	@Transactional(readOnly = true)
	public CustomerAccountResponse creditStatus(UUID customerId) {
		Customer customer = this.customers.load(customerId);
		BigDecimal balance = this.entries.balanceOf(customerId).setScale(2);
		return new CustomerAccountResponse(customer.getId(), customer.getCreditLimit(), balance,
				available(customer.getCreditLimit(), balance), customer.getPaymentTermsDays(), customer.isActive());
	}

	/** Relevé du compte, du plus récent au plus ancien par défaut. */
	@Transactional(readOnly = true)
	public Page<CustomerAccountEntryResponse> entries(UUID customerId, PageQuery query) {
		this.customers.load(customerId);
		return this.entries.findByCustomer(customerId, query.toPageable(SORTS)).map(CustomerAccountService::toResponse);
	}

	/**
	 * Vente à crédit (module ventes) : le reste dû de la vente augmente la dette.
	 *
	 * @throws BusinessRuleException {@code CREDIT_LIMIT_EXCEEDED}, {@code CUSTOMER_INACTIVE}
	 */
	public CustomerAccountEntryResponse recordCreditSale(UUID customerId, BigDecimal amount, UUID saleId,
			String saleReference) {
		return post(customerId, CustomerAccountEntryType.CREDIT_SALE, amount, saleId, saleReference, null);
	}

	/**
	 * Diminution de la dette : règlement ({@code PAYMENT}), avoir ({@code CREDIT_NOTE}) ou
	 * annulation de vente à crédit ({@code SALE_CANCELLATION}).
	 *
	 * @throws BusinessRuleException {@code AMOUNT_EXCEEDS_CUSTOMER_BALANCE}
	 */
	public CustomerAccountEntryResponse recordCredit(UUID customerId, CustomerAccountEntryType type, BigDecimal amount,
			UUID sourceId, String sourceReference, String note) {
		if (type.direction() != CustomerAccountEntryType.Direction.CREDIT) {
			throw new IllegalArgumentException(type + " n'est pas une écriture au crédit.");
		}
		return post(customerId, type, amount, sourceId, sourceReference, note);
	}

	// --- Cœur ---------------------------------------------------------------------------

	private CustomerAccountEntryResponse post(UUID customerId, CustomerAccountEntryType type, BigDecimal amount,
			UUID sourceId, String sourceReference, String note) {
		UUID actor = ActorIdentity.currentUserId()
			.orElseThrow(() -> new UnauthorizedException(ErrorCode.UNAUTHENTICATED));
		BigDecimal value = validAmount(amount);

		Customer customer = this.customers.load(customerId);
		// Relu sous verrou : état et plafond à jour, écritures concurrentes sérialisées.
		this.entityManager.refresh(customer, LockModeType.PESSIMISTIC_WRITE);
		if (type.direction() == CustomerAccountEntryType.Direction.DEBIT && !customer.isActive()) {
			throw new BusinessRuleException(ErrorCode.CUSTOMER_INACTIVE,
					"Ce client est désactivé : il ne peut plus acheter à crédit.");
		}
		if (this.entries.existsBySource(type.sourceType(), sourceId, type)) {
			throw new ConflictException(ErrorCode.CUSTOMER_ACCOUNT_ENTRY_ALREADY_RECORDED,
					ErrorCode.CUSTOMER_ACCOUNT_ENTRY_ALREADY_RECORDED.defaultMessage());
		}

		BigDecimal balance = this.entries.balanceOf(customerId).setScale(2);
		BigDecimal balanceAfter;
		if (type.direction() == CustomerAccountEntryType.Direction.DEBIT) {
			balanceAfter = balance.add(value);
			if (balanceAfter.compareTo(customer.getCreditLimit()) > 0) {
				Map<String, Object> details = new LinkedHashMap<>();
				details.put("customerId", customerId);
				details.put("creditLimit", customer.getCreditLimit());
				details.put("balance", balance);
				details.put("available", available(customer.getCreditLimit(), balance));
				details.put("requested", value);
				throw new BusinessRuleException(ErrorCode.CREDIT_LIMIT_EXCEEDED,
						ErrorCode.CREDIT_LIMIT_EXCEEDED.defaultMessage(), details);
			}
		}
		else {
			if (value.compareTo(balance) > 0) {
				Map<String, Object> details = new LinkedHashMap<>();
				details.put("customerId", customerId);
				details.put("balance", balance);
				details.put("requested", value);
				throw new BusinessRuleException(ErrorCode.AMOUNT_EXCEEDS_CUSTOMER_BALANCE,
						ErrorCode.AMOUNT_EXCEEDS_CUSTOMER_BALANCE.defaultMessage(), details);
			}
			balanceAfter = balance.subtract(value);
		}

		CustomerAccountEntry entry = this.entries.save(new CustomerAccountEntry(customerId, type, value, balanceAfter,
				sourceId, StringUtils.hasText(sourceReference) ? sourceReference.trim() : null,
				StringUtils.hasText(note) ? note.trim() : null, Instant.now(this.clock), actor));
		this.entityManager.flush();
		return toResponse(entry);
	}

	private static BigDecimal validAmount(BigDecimal amount) {
		if (amount == null || amount.signum() <= 0) {
			throw new InvalidRequestException(ErrorCode.INVALID_PARAMETER, "Montant invalide : il doit être positif.");
		}
		try {
			return amount.setScale(2, RoundingMode.UNNECESSARY);
		}
		catch (ArithmeticException ex) {
			throw new InvalidRequestException(ErrorCode.INVALID_PARAMETER,
					"Montant invalide : deux décimales au plus.");
		}
	}

	private static BigDecimal available(BigDecimal creditLimit, BigDecimal balance) {
		return creditLimit.subtract(balance).max(BigDecimal.ZERO.setScale(2));
	}

	static CustomerAccountEntryResponse toResponse(CustomerAccountEntry entry) {
		return new CustomerAccountEntryResponse(entry.getId(), entry.getCustomerId(), entry.getType(),
				entry.getDirection(), entry.getAmount(), entry.getBalanceAfter(), entry.getSourceType(),
				entry.getSourceId(), entry.getSourceReference(), entry.getNote(), entry.getCreatedAt(),
				entry.getCreatedBy());
	}

}
