package com.geststock.stock;

import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.query.QueryUtils;
import org.springframework.stereotype.Component;

/**
 * Recherche paginée <b>en lecture seule</b> sur les entités du stock. Remplace
 * {@code JpaSpecificationExecutor}, qui expose aussi des écritures en masse
 * ({@code update}, {@code delete}) : aucune voie d'écriture du stock ne doit exister hors
 * de {@link StockService}.
 */
@Component
class StockReadQueries {

	private final EntityManager entityManager;

	StockReadQueries(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	<T> Page<T> findAll(Class<T> type, Specification<T> spec, Pageable pageable) {
		CriteriaBuilder cb = this.entityManager.getCriteriaBuilder();

		CriteriaQuery<T> select = cb.createQuery(type);
		Root<T> root = select.from(type);
		Predicate predicate = spec.toPredicate(root, select, cb);
		if (predicate != null) {
			select.where(predicate);
		}
		select.orderBy(QueryUtils.toOrders(pageable.getSort(), root, cb));
		TypedQuery<T> query = this.entityManager.createQuery(select);
		if (pageable.isPaged()) {
			query.setFirstResult((int) pageable.getOffset());
			query.setMaxResults(pageable.getPageSize());
		}
		List<T> content = query.getResultList();

		CriteriaQuery<Long> count = cb.createQuery(Long.class);
		Root<T> countRoot = count.from(type);
		Predicate countPredicate = spec.toPredicate(countRoot, count, cb);
		if (countPredicate != null) {
			count.where(countPredicate);
		}
		count.select(cb.count(countRoot));
		long total = this.entityManager.createQuery(count).getSingleResult();
		return new PageImpl<>(content, pageable, total);
	}

}
