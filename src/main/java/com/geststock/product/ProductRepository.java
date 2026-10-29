package com.geststock.product;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Accès aux produits, réservé au module {@code product}. */
interface ProductRepository extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {

	/** Référence déjà prise (casse ignorée, index {@code uk_product_sku}), hors le produit indiqué. */
	@Query("""
			select count(p) > 0 from Product p
			where lower(p.sku) = lower(:sku) and (:excludedId is null or p.id <> :excludedId)""")
	boolean existsBySkuIgnoringCase(@Param("sku") String sku, @Param("excludedId") UUID excludedId);

	@Query("""
			select count(p) > 0 from Product p
			where p.barcode = :barcode and (:excludedId is null or p.id <> :excludedId)""")
	boolean existsByBarcode(@Param("barcode") String barcode, @Param("excludedId") UUID excludedId);

	/** Pagination par clé de l'instantané d'intégration : archivés compris, par identifiant croissant. */
	@Query("select p from Product p where p.id > :afterId order by p.id")
	List<Product> findAfter(@Param("afterId") UUID afterId, Limit limit);

	/** Identifiants seulement, même ordre : sections images et disponibilité de l'instantané. */
	@Query("select p.id from Product p where p.id > :afterId order by p.id")
	List<UUID> findIdsAfter(@Param("afterId") UUID afterId, Limit limit);

}
