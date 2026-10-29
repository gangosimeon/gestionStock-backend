package com.geststock.category;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Accès aux catégories, réservé au module {@code category}. */
interface CategoryRepository extends JpaRepository<Category, UUID>, JpaSpecificationExecutor<Category> {

	/** Nom déjà pris (casse ignorée, index {@code uk_category_name}), hors l'élément indiqué. */
	@Query("""
			select count(c) > 0 from Category c
			where lower(c.name) = lower(:name) and (:excludedId is null or c.id <> :excludedId)""")
	boolean existsByNameIgnoringCase(@Param("name") String name, @Param("excludedId") UUID excludedId);

	/** Pagination par clé de l'instantané d'intégration : archivées comprises, par identifiant croissant. */
	@Query("select c from Category c where c.id > :afterId order by c.id")
	List<Category> findAfter(@Param("afterId") UUID afterId, Limit limit);

}
