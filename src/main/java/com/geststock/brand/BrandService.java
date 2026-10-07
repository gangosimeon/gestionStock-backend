package com.geststock.brand;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.geststock.brand.dto.BrandRequest;
import com.geststock.brand.dto.BrandResponse;
import com.geststock.common.api.ActiveStatus;
import com.geststock.common.api.PageQuery;
import com.geststock.common.api.SortWhitelist;
import com.geststock.exception.BusinessRuleException;
import com.geststock.exception.ConflictException;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.NotFoundException;

/** Marques (API publique du module {@code brand}). */
@Service
@Transactional(readOnly = true)
public class BrandService {

	static final SortWhitelist SORTS = new SortWhitelist(Map.of("name", "name", "createdAt", "createdAt"),
			Sort.by("name"));

	private final BrandRepository repository;

	BrandService(BrandRepository repository) {
		this.repository = repository;
	}

	public Page<BrandResponse> search(String text, ActiveStatus status, PageQuery query) {
		Specification<Brand> spec = Specification.unrestricted();
		if (StringUtils.hasText(text)) {
			String pattern = "%" + text.trim().toLowerCase(Locale.ROOT) + "%";
			spec = spec.and((root, q, cb) -> cb.like(cb.lower(root.get("name")), pattern));
		}
		Boolean active = (status != null ? status : ActiveStatus.ACTIVE).asActiveFlag();
		if (active != null) {
			spec = spec.and((root, q, cb) -> cb.equal(root.get("active"), active));
		}
		return this.repository.findAll(spec, query.toPageable(SORTS)).map(BrandService::toResponse);
	}

	public BrandResponse get(UUID id) {
		return toResponse(load(id));
	}

	@Transactional
	public BrandResponse create(BrandRequest request) {
		String name = request.name().trim();
		requireUniqueName(name, null);
		return toResponse(this.repository.saveAndFlush(new Brand(name)));
	}

	@Transactional
	public BrandResponse update(UUID id, BrandRequest request) {
		Brand brand = load(id);
		String name = request.name().trim();
		requireUniqueName(name, id);
		brand.rename(name);
		return toResponse(this.repository.saveAndFlush(brand));
	}

	/** Une marque désactivée reste attachée à ses produits mais ne peut plus être choisie. */
	@Transactional
	public BrandResponse setActive(UUID id, boolean active) {
		Brand brand = load(id);
		if (active) {
			brand.activate();
		}
		else {
			brand.deactivate();
		}
		return toResponse(this.repository.saveAndFlush(brand));
	}

	/**
	 * Marque à rattacher à un produit (module {@code product}).
	 *
	 * @param requireActive {@code false} quand le produit la porte déjà
	 */
	public Brand getForAssignment(UUID id, boolean requireActive) {
		Brand brand = load(id);
		if (requireActive && !brand.isActive()) {
			throw new BusinessRuleException(ErrorCode.BRAND_INACTIVE, ErrorCode.BRAND_INACTIVE.defaultMessage());
		}
		return brand;
	}

	private Brand load(UUID id) {
		return this.repository.findById(id)
			.orElseThrow(() -> new NotFoundException(ErrorCode.BRAND_NOT_FOUND, ErrorCode.BRAND_NOT_FOUND.defaultMessage()));
	}

	private void requireUniqueName(String name, UUID excludedId) {
		if (this.repository.existsByNameIgnoringCase(name, excludedId)) {
			throw new ConflictException(ErrorCode.BRAND_NAME_ALREADY_USED, ErrorCode.BRAND_NAME_ALREADY_USED.defaultMessage());
		}
	}

	static BrandResponse toResponse(Brand brand) {
		return new BrandResponse(brand.getId(), brand.getName(), brand.isActive(), brand.getCreatedAt(),
				brand.getUpdatedAt());
	}

}
