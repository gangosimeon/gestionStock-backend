package com.geststock.product;

import java.util.UUID;

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

}
