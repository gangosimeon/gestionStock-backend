package com.geststock.product;

import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import com.geststock.common.api.ActiveStatus;
import com.geststock.product.dto.ProductFilter;

/** Traduction de {@link ProductFilter} en critères JPA ; un critère vide est ignoré. */
final class ProductSpecifications {

	private ProductSpecifications() {
	}

	static Specification<Product> matching(ProductFilter filter) {
		Specification<Product> spec = Specification.unrestricted();
		if (StringUtils.hasText(filter.q())) {
			String text = filter.q().trim().toLowerCase(Locale.ROOT);
			String pattern = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
			spec = spec.and((root, query, cb) -> cb.or(
					cb.like(cb.lower(root.get("sku")), pattern, '\\'),
					cb.like(cb.lower(root.get("name")), pattern, '\\'),
					cb.like(root.get("barcode"), pattern, '\\')));
		}
		if (filter.categoryId() != null) {
			spec = spec.and((root, query, cb) -> cb.equal(root.get("category").get("id"), filter.categoryId()));
		}
		if (filter.brandId() != null) {
			spec = spec.and((root, query, cb) -> cb.equal(root.get("brand").get("id"), filter.brandId()));
		}
		if (filter.supplierId() != null) {
			spec = spec.and((root, query, cb) -> cb.equal(root.get("defaultSupplierId"), filter.supplierId()));
		}
		Boolean active = (filter.status() != null ? filter.status() : ActiveStatus.ACTIVE).asActiveFlag();
		if (active != null) {
			spec = spec.and((root, query, cb) -> cb.equal(root.get("active"), active));
		}
		return spec;
	}

}
