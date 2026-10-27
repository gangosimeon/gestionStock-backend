package com.geststock.product;

import java.util.UUID;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Table;

import com.geststock.common.jpa.AuditableEntity;

/**
 * Photo d'un produit (au plus une par produit, {@code product_image}). Stockée en base comme le logo
 * des factures ; toujours un JPEG réduit par {@link ProductImageProcessor}. Les données ne sont lues
 * que pour servir l'image (chargement différé).
 */
@Entity
@Table(name = "product_image")
class ProductImage extends AuditableEntity {

	@Column(name = "product_id", nullable = false, updatable = false)
	private UUID productId;

	@Column(name = "content_type", nullable = false, length = 20)
	private String contentType;

	@Basic(fetch = FetchType.LAZY)
	@Column(name = "data", nullable = false)
	private byte[] data;

	@Column(name = "size_bytes", nullable = false)
	private int sizeBytes;

	@Column(name = "width", nullable = false)
	private int width;

	@Column(name = "height", nullable = false)
	private int height;

	@Column(name = "sha256", nullable = false, length = 64)
	private String sha256;

	protected ProductImage() {
	}

	ProductImage(UUID productId, ProductImageProcessor.Processed image) {
		this.productId = productId;
		replace(image);
	}

	void replace(ProductImageProcessor.Processed image) {
		this.contentType = ProductImageProcessor.CONTENT_TYPE;
		this.data = image.data();
		this.sizeBytes = image.data().length;
		this.width = image.width();
		this.height = image.height();
		this.sha256 = image.sha256();
	}

	UUID productId() {
		return this.productId;
	}

	String contentType() {
		return this.contentType;
	}

	byte[] data() {
		return this.data;
	}

	int sizeBytes() {
		return this.sizeBytes;
	}

	int width() {
		return this.width;
	}

	int height() {
		return this.height;
	}

	String sha256() {
		return this.sha256;
	}

}
