package com.geststock.category;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.geststock.audit.AuditEvent;
import com.geststock.audit.AuditService;
import com.geststock.category.dto.CategoryRequest;
import com.geststock.category.dto.CategoryResponse;
import com.geststock.common.api.ActiveStatus;
import com.geststock.common.api.PageQuery;
import com.geststock.common.api.SortWhitelist;
import com.geststock.exception.BusinessRuleException;
import com.geststock.exception.ConflictException;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.NotFoundException;

/** Catégories de produits (API publique du module {@code category}). */
@Service
@Transactional(readOnly = true)
public class CategoryService {

	static final SortWhitelist SORTS = new SortWhitelist(Map.of("name", "name", "createdAt", "createdAt"),
			Sort.by("name"));

	private final CategoryRepository repository;

	private final AuditService audit;

	private final CategoryIntegrationEvents integration;

	CategoryService(CategoryRepository repository, AuditService audit, CategoryIntegrationEvents integration) {
		this.repository = repository;
		this.audit = audit;
		this.integration = integration;
	}

	public Page<CategoryResponse> search(String text, ActiveStatus status, PageQuery query) {
		Specification<Category> spec = Specification.unrestricted();
		if (StringUtils.hasText(text)) {
			// Jokers saisis (%, _) cherchés tels quels, comme dans les autres modules.
			String escaped = text.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%")
				.replace("_", "\\_");
			String pattern = "%" + escaped + "%";
			spec = spec.and((root, q, cb) -> cb.or(cb.like(cb.lower(root.get("name")), pattern, '\\'),
					cb.like(cb.lower(cb.coalesce(root.get("description"), "")), pattern, '\\')));
		}
		Boolean active = (status != null ? status : ActiveStatus.ACTIVE).asActiveFlag();
		if (active != null) {
			spec = spec.and((root, q, cb) -> cb.equal(root.get("active"), active));
		}
		return this.repository.findAll(spec, query.toPageable(SORTS)).map(CategoryService::toResponse);
	}

	public CategoryResponse get(UUID id) {
		return toResponse(load(id));
	}

	@Transactional
	public CategoryResponse create(CategoryRequest request) {
		String name = request.name().trim();
		requireUniqueName(name, null);
		Category saved = this.repository.saveAndFlush(new Category(name, blankToNull(request.description())));
		this.integration.publishIfChanged(null, saved);
		CategoryResponse created = toResponse(saved);
		this.audit.record(AuditEvent.of("CREATE", "CATEGORY", created.id(), name).after(created));
		return created;
	}

	@Transactional
	public CategoryResponse update(UUID id, CategoryRequest request) {
		Category category = load(id);
		CategoryResponse before = toResponse(category);
		CategoryIntegrationEvents.Exposed exposedBefore = CategoryIntegrationEvents.Exposed.of(category);
		String name = request.name().trim();
		requireUniqueName(name, id);
		category.update(name, blankToNull(request.description()));
		Category saved = this.repository.saveAndFlush(category);
		this.integration.publishIfChanged(exposedBefore, saved);
		CategoryResponse updated = toResponse(saved);
		this.audit.record(AuditEvent.of("UPDATE", "CATEGORY", id, name).before(before).after(updated));
		return updated;
	}

	/**
	 * Active ou désactive. Une catégorie désactivée reste attachée à ses produits mais ne peut
	 * plus être choisie pour un autre produit.
	 */
	@Transactional
	public CategoryResponse setActive(UUID id, boolean active) {
		Category category = load(id);
		boolean wasActive = category.isActive();
		CategoryIntegrationEvents.Exposed exposedBefore = CategoryIntegrationEvents.Exposed.of(category);
		if (active) {
			category.activate();
		}
		else {
			category.deactivate();
		}
		Category flushed = this.repository.saveAndFlush(category);
		this.integration.publishIfChanged(exposedBefore, flushed);
		CategoryResponse saved = toResponse(flushed);
		if (wasActive != active) {
			this.audit.record(AuditEvent.of(active ? "ACTIVATE" : "DEACTIVATE", "CATEGORY", id, saved.name())
				.before(java.util.Map.of("active", wasActive))
				.after(java.util.Map.of("active", active)));
		}
		return saved;
	}

	/**
	 * Catégorie à rattacher à un produit (module {@code product}).
	 *
	 * @param requireActive {@code false} quand le produit la porte déjà : elle reste valable même désactivée
	 */
	public Category getForAssignment(UUID id, boolean requireActive) {
		Category category = load(id);
		if (requireActive && !category.isActive()) {
			throw new BusinessRuleException(ErrorCode.CATEGORY_INACTIVE, ErrorCode.CATEGORY_INACTIVE.defaultMessage());
		}
		return category;
	}

	private Category load(UUID id) {
		return this.repository.findById(id)
			.orElseThrow(() -> new NotFoundException(ErrorCode.CATEGORY_NOT_FOUND,
					ErrorCode.CATEGORY_NOT_FOUND.defaultMessage()));
	}

	private void requireUniqueName(String name, UUID excludedId) {
		if (this.repository.existsByNameIgnoringCase(name, excludedId)) {
			throw new ConflictException(ErrorCode.CATEGORY_NAME_ALREADY_USED,
					ErrorCode.CATEGORY_NAME_ALREADY_USED.defaultMessage());
		}
	}

	static CategoryResponse toResponse(Category category) {
		return new CategoryResponse(category.getId(), category.getName(), category.getDescription(), category.isActive(),
				category.getCreatedAt(), category.getUpdatedAt());
	}

	private static String blankToNull(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

}
