package com.geststock.supplier;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.geststock.common.api.ActiveStatus;
import com.geststock.common.api.PageQuery;
import com.geststock.common.api.SortWhitelist;
import com.geststock.exception.BusinessRuleException;
import com.geststock.exception.ConflictException;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.NotFoundException;
import com.geststock.supplier.dto.SupplierFilter;
import com.geststock.supplier.dto.SupplierRequest;
import com.geststock.supplier.dto.SupplierResponse;

/**
 * Fournisseurs (API publique du module {@code supplier}).
 *
 * <p>Règles :
 * <ul>
 * <li>nom unique sans tenir compte de la casse ; code unique, en majuscules, généré
 * ({@code FOU-0001}) s'il n'est pas fourni ;</li>
 * <li>délais de livraison et de paiement entre 0 et 365 jours ;</li>
 * <li>désactivation plutôt que suppression : un fournisseur désactivé reste attaché à ses
 * produits et à ses pièces d'achat, mais ne peut plus être choisi
 * ({@link #getForAssignment}).</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class SupplierService {

	static final SortWhitelist SORTS = new SortWhitelist(Map.of(
			"name", "name",
			"code", "code",
			"deliveryLeadTimeDays", "deliveryLeadTimeDays",
			"createdAt", "createdAt"), Sort.by("name"));

	static final String CODE_PREFIX = "FOU-";

	private final SupplierRepository repository;

	SupplierService(SupplierRepository repository) {
		this.repository = repository;
	}

	public Page<SupplierResponse> search(SupplierFilter filter, PageQuery query) {
		Specification<Supplier> spec = Specification.unrestricted();
		if (StringUtils.hasText(filter.q())) {
			String text = filter.q().trim().toLowerCase(Locale.ROOT);
			String pattern = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
			spec = spec.and((root, q, cb) -> cb.or(
					cb.like(cb.lower(root.get("code")), pattern, '\\'),
					cb.like(cb.lower(root.get("name")), pattern, '\\'),
					cb.like(cb.lower(root.get("contactName")), pattern, '\\'),
					cb.like(cb.lower(root.get("email")), pattern, '\\'),
					cb.like(root.get("phone"), pattern, '\\')));
		}
		Boolean active = (filter.status() != null ? filter.status() : ActiveStatus.ACTIVE).asActiveFlag();
		if (active != null) {
			spec = spec.and((root, q, cb) -> cb.equal(root.get("active"), active));
		}
		return this.repository.findAll(spec, query.toPageable(SORTS)).map(SupplierService::toResponse);
	}

	public SupplierResponse get(UUID id) {
		return toResponse(load(id));
	}

	@Transactional
	public SupplierResponse create(SupplierRequest request) {
		String name = request.name().trim();
		requireUniqueName(name, null);
		String code = StringUtils.hasText(request.code()) ? normalizeCode(request.code()) : nextCode();
		requireUniqueCode(code, null);

		Supplier supplier = new Supplier(code, name);
		supplier.changeContact(blankToNull(request.contactName()), blankToNull(request.phone()),
				blankToNull(request.email()), blankToNull(request.address()));
		supplier.changeTerms(blankToNull(request.taxId()), orZero(request.deliveryLeadTimeDays()),
				orZero(request.paymentTermsDays()));
		supplier.annotate(blankToNull(request.note()));
		return toResponse(this.repository.saveAndFlush(supplier));
	}

	/**
	 * Modification. Champs du front remplacés ; champs étendus conservés s'ils sont absents
	 * (voir {@link SupplierRequest}).
	 */
	@Transactional
	public SupplierResponse update(UUID id, SupplierRequest request) {
		Supplier supplier = load(id);
		String name = request.name().trim();
		requireUniqueName(name, id);
		String code = StringUtils.hasText(request.code()) ? normalizeCode(request.code()) : supplier.getCode();
		requireUniqueCode(code, id);

		supplier.identify(code, name);
		supplier.changeContact(keepIfAbsent(request.contactName(), supplier.getContactName()),
				blankToNull(request.phone()), blankToNull(request.email()), blankToNull(request.address()));
		supplier.changeTerms(keepIfAbsent(request.taxId(), supplier.getTaxId()),
				request.deliveryLeadTimeDays() != null ? request.deliveryLeadTimeDays() : supplier.getDeliveryLeadTimeDays(),
				request.paymentTermsDays() != null ? request.paymentTermsDays() : supplier.getPaymentTermsDays());
		supplier.annotate(keepIfAbsent(request.note(), supplier.getNote()));
		return toResponse(this.repository.saveAndFlush(supplier));
	}

	/** Désactive (« supprime ») ou réactive. */
	@Transactional
	public SupplierResponse setActive(UUID id, boolean active) {
		Supplier supplier = load(id);
		if (active) {
			supplier.activate();
		}
		else {
			supplier.deactivate();
		}
		return toResponse(this.repository.saveAndFlush(supplier));
	}

	/**
	 * Fournisseur à rattacher à un produit ou à une pièce d'achat (autres modules).
	 *
	 * @param requireActive {@code false} quand l'élément le porte déjà (un fournisseur
	 *                      désactivé depuis reste valable pour l'existant)
	 * @throws NotFoundException     {@code SUPPLIER_NOT_FOUND} s'il n'existe pas
	 * @throws BusinessRuleException {@code SUPPLIER_INACTIVE} s'il est désactivé et que
	 *                               {@code requireActive} est vrai
	 */
	public SupplierSnapshot getForAssignment(UUID id, boolean requireActive) {
		Supplier supplier = load(id);
		if (requireActive && !supplier.isActive()) {
			throw new BusinessRuleException(ErrorCode.SUPPLIER_INACTIVE, ErrorCode.SUPPLIER_INACTIVE.defaultMessage());
		}
		return SupplierSnapshot.of(supplier);
	}

	// --- Outils -------------------------------------------------------------------------

	private Supplier load(UUID id) {
		return this.repository.findById(id)
			.orElseThrow(() -> new NotFoundException(ErrorCode.SUPPLIER_NOT_FOUND,
					ErrorCode.SUPPLIER_NOT_FOUND.defaultMessage()));
	}

	private void requireUniqueName(String name, UUID excludedId) {
		if (this.repository.existsByNameIgnoringCase(name, excludedId)) {
			throw new ConflictException(ErrorCode.SUPPLIER_NAME_ALREADY_USED,
					ErrorCode.SUPPLIER_NAME_ALREADY_USED.defaultMessage());
		}
	}

	private void requireUniqueCode(String code, UUID excludedId) {
		if (this.repository.existsByCode(code, excludedId)) {
			throw new ConflictException(ErrorCode.SUPPLIER_CODE_ALREADY_USED,
					ErrorCode.SUPPLIER_CODE_ALREADY_USED.defaultMessage());
		}
	}

	/** Code issu de la séquence ; saute les valeurs déjà prises par un code saisi à la main. */
	private String nextCode() {
		String code;
		do {
			code = CODE_PREFIX + String.format(Locale.ROOT, "%04d", this.repository.nextCodeNumber());
		}
		while (this.repository.existsByCode(code, null));
		return code;
	}

	private static String normalizeCode(String code) {
		return code.trim().toUpperCase(Locale.ROOT);
	}

	private static String blankToNull(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

	/** Absent : valeur actuelle ; vide : effacé ; sinon la nouvelle valeur. */
	private static String keepIfAbsent(String value, String current) {
		return value == null ? current : blankToNull(value);
	}

	private static int orZero(Integer value) {
		return value != null ? value : 0;
	}

	static SupplierResponse toResponse(Supplier supplier) {
		return new SupplierResponse(supplier.getId(), supplier.getCode(), supplier.getName(),
				supplier.getContactName(), supplier.getPhone(), supplier.getEmail(), supplier.getAddress(),
				supplier.getTaxId(), supplier.getDeliveryLeadTimeDays(), supplier.getPaymentTermsDays(),
				supplier.getNote(), supplier.isActive(), supplier.getCreatedAt(), supplier.getUpdatedAt());
	}

}
