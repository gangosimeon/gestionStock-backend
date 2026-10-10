package com.geststock.supplier;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Accès aux fournisseurs, réservé au module {@code supplier}. */
interface SupplierRepository extends JpaRepository<Supplier, UUID>, JpaSpecificationExecutor<Supplier> {

	/** Nom déjà pris (casse ignorée, index {@code uk_supplier_name}), hors l'élément indiqué. */
	@Query("""
			select count(s) > 0 from Supplier s
			where lower(s.name) = lower(:name) and (:excludedId is null or s.id <> :excludedId)""")
	boolean existsByNameIgnoringCase(@Param("name") String name, @Param("excludedId") UUID excludedId);

	/** Code déjà pris (index {@code uk_supplier_code}), hors l'élément indiqué. */
	@Query("""
			select count(s) > 0 from Supplier s
			where s.code = :code and (:excludedId is null or s.id <> :excludedId)""")
	boolean existsByCode(@Param("code") String code, @Param("excludedId") UUID excludedId);

	/** Prochaine valeur de la séquence des codes générés (jamais {@code max() + 1}). */
	@Query(value = "select nextval('seq_supplier_code')", nativeQuery = true)
	long nextCodeNumber();

}
