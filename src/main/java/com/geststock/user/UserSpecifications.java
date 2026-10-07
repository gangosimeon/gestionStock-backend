package com.geststock.user;

import java.util.Locale;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Subquery;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import com.geststock.role.Role;
import com.geststock.user.dto.UserFilter;

/** Traduction de {@link UserFilter} en critères JPA ; un critère vide est ignoré. */
final class UserSpecifications {

	private UserSpecifications() {
	}

	static Specification<AppUser> matching(UserFilter filter) {
		Specification<AppUser> spec = Specification.unrestricted();
		if (filter == null) {
			return spec;
		}
		if (StringUtils.hasText(filter.q())) {
			spec = spec.and(textContains(filter.q()));
		}
		if (filter.active() != null) {
			spec = spec.and((root, query, cb) -> cb.equal(root.get("active"), filter.active()));
		}
		if (StringUtils.hasText(filter.role())) {
			spec = spec.and(hasRole(filter.role().trim().toUpperCase(Locale.ROOT)));
		}
		return spec;
	}

	/** Recherche dans l'identifiant, le nom, le prénom et l'email. */
	private static Specification<AppUser> textContains(String text) {
		String pattern = "%" + text.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
				+ "%";
		return (root, query, cb) -> cb.or(
				cb.like(cb.lower(root.get("username")), pattern, '\\'),
				cb.like(cb.lower(root.get("lastName")), pattern, '\\'),
				cb.like(cb.lower(root.get("firstName")), pattern, '\\'),
				cb.like(cb.lower(root.get("email")), pattern, '\\'));
	}

	/** Sous-requête : évite les doublons qu'une jointure directe produirait sur la page. */
	private static Specification<AppUser> hasRole(String roleCode) {
		return (root, query, cb) -> {
			Subquery<Long> sub = query.subquery(Long.class);
			var subUser = sub.from(AppUser.class);
			Join<AppUser, Role> roles = subUser.join("roles", JoinType.INNER);
			sub.select(cb.literal(1L))
				.where(cb.equal(subUser.get("id"), root.get("id")), cb.equal(roles.get("code"), roleCode));
			return cb.exists(sub);
		};
	}

}
