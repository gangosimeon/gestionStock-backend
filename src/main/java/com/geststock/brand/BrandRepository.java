package com.geststock.brand;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Accès aux marques, réservé au module {@code brand}. */
interface BrandRepository extends JpaRepository<Brand, UUID>, JpaSpecificationExecutor<Brand> {

	/** Nom déjà pris (casse ignorée, index {@code uk_brand_name}), hors l'élément indiqué. */
	@Query("""
			select count(b) > 0 from Brand b
			where lower(b.name) = lower(:name) and (:excludedId is null or b.id <> :excludedId)""")
	boolean existsByNameIgnoringCase(@Param("name") String name, @Param("excludedId") UUID excludedId);

}
