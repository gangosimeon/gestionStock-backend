package com.geststock.product;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.geststock.exception.ErrorCode;
import com.geststock.exception.NotFoundException;
import com.geststock.product.dto.ProductImageInfo;

/**
 * Photo facultative des produits : ajout ou remplacement (image réduite et ré-encodée par le serveur),
 * retrait, lecture. La photo est indépendante de la fiche produit : la changer ne modifie pas le produit
 * (pas de conflit avec une modification de fiche en cours).
 */
@Service
public class ProductImageService {

	/** Image servie au navigateur. */
	public record StoredImage(String contentType, byte[] data, String sha256) {
	}

	private final ProductImageRepository images;

	private final ProductRepository products;

	ProductImageService(ProductImageRepository images, ProductRepository products) {
		this.images = images;
		this.products = products;
	}

	@Transactional
	public ProductImageInfo upload(UUID productId, byte[] file) {
		requireProduct(productId);
		ProductImageProcessor.Processed processed = ProductImageProcessor.process(file);
		ProductImage image = this.images.findByProductId(productId).orElse(null);
		if (image == null) {
			image = new ProductImage(productId, processed);
		}
		else {
			image.replace(processed);
		}
		ProductImage saved = this.images.save(image);
		return new ProductImageInfo(saved.productId(), saved.contentType(), saved.sizeBytes(), saved.width(),
				saved.height(), saved.sha256());
	}

	@Transactional
	public void delete(UUID productId) {
		requireProduct(productId);
		this.images.findByProductId(productId).ifPresent(this.images::delete);
	}

	@Transactional(readOnly = true)
	public StoredImage get(UUID productId) {
		ProductImage image = this.images.findByProductId(productId)
			.orElseThrow(() -> new NotFoundException(ErrorCode.PRODUCT_IMAGE_NOT_FOUND,
					ErrorCode.PRODUCT_IMAGE_NOT_FOUND.defaultMessage()));
		return new StoredImage(image.contentType(), image.data(), image.sha256());
	}

	/** Version de la photo de chaque produit qui en a une (empreinte), sans lire les images. */
	@Transactional(readOnly = true)
	public Map<UUID, String> versions(Collection<UUID> productIds) {
		if (productIds.isEmpty()) {
			return Map.of();
		}
		return this.images.findVersions(productIds).stream()
			.collect(Collectors.toMap(ProductImageRepository.ImageVersion::getProductId,
					ProductImageRepository.ImageVersion::getSha256));
	}

	private void requireProduct(UUID productId) {
		if (!this.products.existsById(productId)) {
			throw new NotFoundException(ErrorCode.PRODUCT_NOT_FOUND, ErrorCode.PRODUCT_NOT_FOUND.defaultMessage());
		}
	}

}
