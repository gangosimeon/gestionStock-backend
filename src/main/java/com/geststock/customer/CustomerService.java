package com.geststock.customer;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.criteria.Expression;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.geststock.common.api.ActiveStatus;
import com.geststock.common.api.PageQuery;
import com.geststock.common.api.SortWhitelist;
import com.geststock.common.security.ActorPermissions;
import com.geststock.common.security.Permissions;
import com.geststock.customer.dto.CustomerFilter;
import com.geststock.customer.dto.CustomerRequest;
import com.geststock.customer.dto.CustomerResponse;
import com.geststock.exception.BusinessRuleException;
import com.geststock.exception.ConflictException;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.ForbiddenOperationException;
import com.geststock.exception.NotFoundException;

/**
 * Clients (API publique du module {@code customer}).
 *
 * <p>Règles :
 * <ul>
 * <li>nom obligatoire ; code unique, en majuscules, généré ({@code CLI-000001}) s'il est absent ;
 * pas d'unicité sur le nom ni le téléphone (homonymes, téléphone familial) ;</li>
 * <li>plafond de crédit ≥ 0, 0 par défaut (aucun crédit) ; l'accorder ou le modifier exige
 * {@code customer:credit} (le caissier crée et modifie les clients, pas leur crédit) ;</li>
 * <li>désactivation plutôt que suppression ; un client désactivé ne peut plus acheter à
 * crédit mais peut toujours régler sa dette ({@link CustomerAccountService}).</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class CustomerService {

	static final SortWhitelist SORTS = new SortWhitelist(Map.of(
			"name", "name",
			"code", "code",
			"creditLimit", "creditLimit",
			"createdAt", "createdAt"), Sort.by("name"));

	static final String CODE_PREFIX = "CLI-";

	private final CustomerRepository repository;

	private final CustomerAccountEntryRepository entries;

	CustomerService(CustomerRepository repository, CustomerAccountEntryRepository entries) {
		this.repository = repository;
		this.entries = entries;
	}

	public Page<CustomerResponse> search(CustomerFilter filter, PageQuery query) {
		Page<Customer> page = this.repository.findAll(matching(filter), query.toPageable(SORTS));
		Map<UUID, BigDecimal> balances = balancesOf(page.getContent());
		return page.map(customer -> toResponse(customer, balances.getOrDefault(customer.getId(), BigDecimal.ZERO)));
	}

	public CustomerResponse get(UUID id) {
		Customer customer = load(id);
		return toResponse(customer, this.entries.balanceOf(id));
	}

	@Transactional
	public CustomerResponse create(CustomerRequest request) {
		BigDecimal creditLimit = request.creditLimit() != null ? request.creditLimit() : BigDecimal.ZERO;
		if (creditLimit.signum() > 0) {
			requireCreditPermission();
		}
		String code = StringUtils.hasText(request.code()) ? normalizeCode(request.code()) : nextCode();
		requireUniqueCode(code, null);

		CustomerType type = request.type() != null ? request.type() : CustomerType.INDIVIDUAL;
		String name = request.name().trim();
		Customer customer = new Customer(code, type, name);
		customer.identify(code, type, name, blankToNull(request.taxId()));
		customer.changeContact(blankToNull(request.phone()), blankToNull(request.email()),
				blankToNull(request.address()), blankToNull(request.city()));
		customer.changeTerms(request.priceCategory() != null ? request.priceCategory() : PriceCategory.RETAIL,
				creditLimit, request.paymentTermsDays() != null ? request.paymentTermsDays() : 0);
		customer.annotate(blankToNull(request.note()));
		return toResponse(this.repository.saveAndFlush(customer), BigDecimal.ZERO);
	}

	/** Modification (PUT ou PATCH du front) ; voir {@link CustomerRequest} pour les champs conservés. */
	@Transactional
	public CustomerResponse update(UUID id, CustomerRequest request) {
		Customer customer = load(id);
		String code = StringUtils.hasText(request.code()) ? normalizeCode(request.code()) : customer.getCode();
		requireUniqueCode(code, id);
		BigDecimal creditLimit = request.creditLimit() != null ? request.creditLimit() : customer.getCreditLimit();
		if (creditLimit.compareTo(customer.getCreditLimit()) != 0) {
			requireCreditPermission();
		}

		customer.identify(code, request.type() != null ? request.type() : customer.getType(), request.name().trim(),
				keepIfAbsent(request.taxId(), customer.getTaxId()));
		customer.changeContact(blankToNull(request.phone()), blankToNull(request.email()),
				keepIfAbsent(request.address(), customer.getAddress()), keepIfAbsent(request.city(), customer.getCity()));
		customer.changeTerms(request.priceCategory() != null ? request.priceCategory() : customer.getPriceCategory(),
				creditLimit,
				request.paymentTermsDays() != null ? request.paymentTermsDays() : customer.getPaymentTermsDays());
		customer.annotate(keepIfAbsent(request.note(), customer.getNote()));
		Customer saved = this.repository.saveAndFlush(customer);
		return toResponse(saved, this.entries.balanceOf(id));
	}

	/**
	 * Désactive (« supprime ») ou réactive. Un client désactivé disparaît des listes et ne peut
	 * plus acheter à crédit ; son historique et sa dette restent consultables.
	 */
	@Transactional
	public CustomerResponse setActive(UUID id, boolean active) {
		Customer customer = load(id);
		if (active) {
			customer.activate();
		}
		else {
			customer.deactivate();
		}
		return toResponse(this.repository.saveAndFlush(customer), this.entries.balanceOf(id));
	}

	/**
	 * Client à rattacher à une pièce (vente, paiement, retour) d'un autre module.
	 *
	 * @param requireActive {@code true} pour une nouvelle vente ; {@code false} pour régler ou
	 *                      retourner sur un client désactivé depuis
	 * @throws NotFoundException     {@code CUSTOMER_NOT_FOUND}
	 * @throws BusinessRuleException {@code CUSTOMER_INACTIVE}
	 */
	public CustomerSnapshot getForAssignment(UUID id, boolean requireActive) {
		Customer customer = load(id);
		if (requireActive && !customer.isActive()) {
			throw new BusinessRuleException(ErrorCode.CUSTOMER_INACTIVE, ErrorCode.CUSTOMER_INACTIVE.defaultMessage());
		}
		return CustomerSnapshot.of(customer);
	}

	// --- Outils -------------------------------------------------------------------------

	Customer load(UUID id) {
		return this.repository.findById(id)
			.orElseThrow(() -> new NotFoundException(ErrorCode.CUSTOMER_NOT_FOUND,
					ErrorCode.CUSTOMER_NOT_FOUND.defaultMessage()));
	}

	private static Specification<Customer> matching(CustomerFilter filter) {
		Specification<Customer> spec = Specification.unrestricted();
		if (StringUtils.hasText(filter.q())) {
			String text = filter.q().trim().toLowerCase(Locale.ROOT);
			String pattern = "%" + escape(text) + "%";
			String digits = text.replaceAll("[^0-9]", "");
			spec = spec.and((root, query, cb) -> {
				Expression<String> phoneDigits = cb.function("regexp_replace", String.class, root.get("phone"),
						cb.literal("[^0-9]"), cb.literal(""), cb.literal("g"));
				return cb.or(
						cb.like(cb.lower(root.get("code")), pattern, '\\'),
						cb.like(cb.lower(root.get("name")), pattern, '\\'),
						cb.like(cb.lower(root.get("email")), pattern, '\\'),
						cb.like(root.get("phone"), pattern, '\\'),
						digits.length() >= 3 ? cb.like(phoneDigits, "%" + digits + "%") : cb.disjunction());
			});
		}
		Boolean active = (filter.status() != null ? filter.status() : ActiveStatus.ACTIVE).asActiveFlag();
		if (active != null) {
			spec = spec.and((root, query, cb) -> cb.equal(root.get("active"), active));
		}
		if (filter.type() != null) {
			spec = spec.and((root, query, cb) -> cb.equal(root.get("type"), filter.type()));
		}
		if (filter.priceCategory() != null) {
			spec = spec.and((root, query, cb) -> cb.equal(root.get("priceCategory"), filter.priceCategory()));
		}
		return spec;
	}

	private Map<UUID, BigDecimal> balancesOf(List<Customer> customers) {
		Map<UUID, BigDecimal> balances = new HashMap<>();
		if (!customers.isEmpty()) {
			for (Object[] row : this.entries.balancesOf(customers.stream().map(Customer::getId).toList())) {
				balances.put((UUID) row[0], (BigDecimal) row[1]);
			}
		}
		return balances;
	}

	private void requireUniqueCode(String code, UUID excludedId) {
		if (this.repository.existsByCode(code, excludedId)) {
			throw new ConflictException(ErrorCode.CUSTOMER_CODE_ALREADY_USED,
					ErrorCode.CUSTOMER_CODE_ALREADY_USED.defaultMessage());
		}
	}

	private static void requireCreditPermission() {
		if (!ActorPermissions.current().contains(Permissions.CUSTOMER_CREDIT)) {
			throw new ForbiddenOperationException(ErrorCode.CREDIT_LIMIT_CHANGE_FORBIDDEN,
					ErrorCode.CREDIT_LIMIT_CHANGE_FORBIDDEN.defaultMessage());
		}
	}

	/** Code issu de la séquence ; saute les valeurs déjà prises par un code saisi à la main. */
	private String nextCode() {
		String code;
		do {
			code = CODE_PREFIX + String.format(Locale.ROOT, "%06d", this.repository.nextCodeNumber());
		}
		while (this.repository.existsByCode(code, null));
		return code;
	}

	private static String escape(String text) {
		return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	private static String normalizeCode(String code) {
		return code.trim().toUpperCase(Locale.ROOT);
	}

	static String blankToNull(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

	/** Absent : valeur actuelle ; vide : effacé ; sinon la nouvelle valeur. */
	private static String keepIfAbsent(String value, String current) {
		return value == null ? current : blankToNull(value);
	}

	static CustomerResponse toResponse(Customer customer, BigDecimal balance) {
		BigDecimal due = balance.setScale(2);
		BigDecimal available = customer.getCreditLimit().subtract(due).max(BigDecimal.ZERO.setScale(2));
		return new CustomerResponse(customer.getId(), customer.getCode(), customer.getType(), customer.getName(),
				customer.getPhone(), customer.getEmail(), customer.getAddress(), customer.getCity(), customer.getTaxId(),
				customer.getPriceCategory(), customer.getCreditLimit(), customer.getPaymentTermsDays(),
				customer.getNote(), customer.isActive(), due, available, customer.getCreatedAt(),
				customer.getUpdatedAt());
	}

}
