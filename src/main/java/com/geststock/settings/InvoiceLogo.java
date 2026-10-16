package com.geststock.settings;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Table;

import com.geststock.common.jpa.AuditableEntity;
import com.geststock.settings.dto.InvoiceSettingsResponse.LogoInfo;

/**
 * Logo de l'entreprise (au plus une ligne de {@code invoice_logo}). L'image est stockée en base :
 * l'application n'a pas d'autre stockage de fichiers, et 2 Mo au plus restent raisonnables.
 */
@Entity
@Table(name = "invoice_logo")
class InvoiceLogo extends AuditableEntity {

	@Column(name = "singleton", nullable = false, updatable = false)
	private boolean singleton = true;

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

	protected InvoiceLogo() {
	}

	InvoiceLogo(LogoImage image) {
		replace(image);
	}

	void replace(LogoImage image) {
		this.contentType = image.contentType();
		this.data = image.data();
		this.sizeBytes = image.data().length;
		this.width = image.width();
		this.height = image.height();
		this.sha256 = image.sha256();
	}

	String contentType() {
		return this.contentType;
	}

	byte[] data() {
		return this.data;
	}

	String sha256() {
		return this.sha256;
	}

	LogoInfo info() {
		return new LogoInfo(this.contentType, this.sizeBytes, this.width, this.height, this.sha256, getUpdatedAt());
	}

}
