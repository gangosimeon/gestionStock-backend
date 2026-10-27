package com.geststock.product;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Photos des produits : zéro ou une par produit. */
interface ProductImageRepository extends Repository<ProductImage, UUID> {

	/** Empreinte de la photo d'un produit (version servie au navigateur). */
	interface ImageVersion {

		UUID getProductId();

		String getSha256();

	}

	@Query("select i from ProductImage i where i.productId = :productId")
	Optional<ProductImage> findByProductId(@Param("productId") UUID productId);

	/** Versions seulement, sans lire les images : pour les listes de produits. */
	@Query("select i.productId as productId, i.sha256 as sha256 from ProductImage i where i.productId in :productIds")
	List<ImageVersion> findVersions(@Param("productIds") Collection<UUID> productIds);

	ProductImage save(ProductImage image);

	void delete(ProductImage image);

}
