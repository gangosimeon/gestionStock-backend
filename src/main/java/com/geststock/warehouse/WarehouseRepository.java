package com.geststock.warehouse;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Accès aux dépôts, réservé au module {@code warehouse}. */
interface WarehouseRepository extends JpaRepository<Warehouse, UUID>, JpaSpecificationExecutor<Warehouse> {

	Optional<Warehouse> findByCode(String code);

	Optional<Warehouse> findByMainTrue();

	boolean existsByCode(String code);

	boolean existsByNameIgnoreCase(String name);

	/** Nom déjà pris (casse ignorée, index {@code uk_warehouse_name}), hors l'élément indiqué. */
	@Query("""
			select count(w) > 0 from Warehouse w
			where lower(w.name) = lower(:name) and (:excludedId is null or w.id <> :excludedId)""")
	boolean existsByNameIgnoringCase(@Param("name") String name, @Param("excludedId") UUID excludedId);

	/** Code déjà pris (codes stockés en majuscules), hors l'élément indiqué. */
	@Query("""
			select count(w) > 0 from Warehouse w
			where w.code = :code and (:excludedId is null or w.id <> :excludedId)""")
	boolean existsByCodeExcluding(@Param("code") String code, @Param("excludedId") UUID excludedId);

	List<Warehouse> findAllByActiveTrueOrderByNameAsc();

	List<Warehouse> findAllByIdInAndActiveTrue(Collection<UUID> ids);

}
