package com.geststock.settings;

import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.geststock.audit.AuditEvent;
import com.geststock.audit.AuditService;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.NotFoundException;
import com.geststock.settings.dto.InvoiceSettings;
import com.geststock.settings.dto.InvoiceSettingsResponse;
import com.geststock.settings.dto.InvoiceSettingsResponse.LogoInfo;

/**
 * Configuration des factures de vente (API publique du module {@code settings}).
 *
 * <p>La configuration est <strong>globale à l'entreprise</strong> : une seule ligne, partagée par
 * tous les dépôts. Elle ne contient aucune donnée propre à un dépôt ; le nom du dépôt de la vente
 * s'affiche sur la facture si {@code showWarehouse} est coché, à partir de la vente elle-même.
 *
 * <p>Les factures sont rendues à la demande à partir de la vente et de la configuration
 * courante : une modification s'applique donc aussi à la réimpression des ventes passées
 * (présentation uniquement : les montants viennent de la vente, jamais d'ici).
 */
@Service
@Transactional(readOnly = true)
public class InvoiceSettingsService {

	static final String ENTITY_TYPE = "INVOICE_SETTING";

	private static final String LABEL = "Configuration de la facture";

	private final InvoiceSettingRepository settings;

	private final InvoiceLogoRepository logos;

	private final AuditService audit;

	InvoiceSettingsService(InvoiceSettingRepository settings, InvoiceLogoRepository logos, AuditService audit) {
		this.settings = settings;
		this.logos = logos;
		this.audit = audit;
	}

	public InvoiceSettingsResponse current() {
		return response(current(false));
	}

	@Transactional
	public InvoiceSettingsResponse update(InvoiceSettings values) {
		InvoiceSetting setting = current(true);
		InvoiceSettings before = setting.values();
		setting.apply(values);
		InvoiceSettings after = setting.values();
		this.audit.record(AuditEvent.of("UPDATE", ENTITY_TYPE, setting.getId(), LABEL)
			.details("Configuration de la facture modifiée.")
			.before(before)
			.after(after));
		this.settings.flush();
		return response(setting);
	}

	/** Revient aux valeurs d'une installation neuve. Le logo est conservé (il se retire à part). */
	@Transactional
	public InvoiceSettingsResponse reset() {
		InvoiceSetting setting = current(true);
		InvoiceSettings before = setting.values();
		setting.apply(InvoiceSettings.defaults());
		InvoiceSettings after = setting.values();
		this.audit.record(AuditEvent.of("RESET", ENTITY_TYPE, setting.getId(), LABEL)
			.details("Configuration de la facture réinitialisée (logo conservé).")
			.before(before)
			.after(after));
		this.settings.flush();
		return response(setting);
	}

	/** Logo enregistré (image et type), ou 404 {@code LOGO_NOT_FOUND}. */
	public StoredLogo logo() {
		InvoiceLogo logo = this.logos.findCurrent()
			.orElseThrow(() -> new NotFoundException(ErrorCode.LOGO_NOT_FOUND, ErrorCode.LOGO_NOT_FOUND.defaultMessage()));
		return new StoredLogo(logo.contentType(), logo.data(), logo.sha256());
	}

	/** Enregistre ou remplace le logo, après vérification du type réel, du poids et des dimensions. */
	@Transactional
	public LogoInfo uploadLogo(byte[] content) {
		LogoImage image = LogoImage.of(content);
		InvoiceSetting setting = current(true);
		Optional<InvoiceLogo> existing = this.logos.findCurrent();
		LogoInfo before = existing.map(InvoiceLogo::info).orElse(null);
		InvoiceLogo logo = existing.orElseGet(() -> new InvoiceLogo(image));
		logo.replace(image);
		logo = this.logos.save(logo);
		this.logos.flush();
		LogoInfo after = logo.info();
		this.audit.record(AuditEvent.of("LOGO_UPLOAD", ENTITY_TYPE, setting.getId(), LABEL)
			.details((before == null ? "Logo ajouté" : "Logo remplacé") + " (" + image.contentType() + ", "
					+ image.width() + " × " + image.height() + " px, " + content.length + " octets).")
			.before(before == null ? null : Map.of("logo", before))
			.after(Map.of("logo", after)));
		return after;
	}

	/** Retire le logo ; sans effet s'il n'y en a pas (l'opération est idempotente). */
	@Transactional
	public void deleteLogo() {
		InvoiceSetting setting = current(true);
		this.logos.findCurrent().ifPresent(logo -> {
			LogoInfo before = logo.info();
			this.logos.delete(logo);
			this.audit.record(AuditEvent.of("LOGO_DELETE", ENTITY_TYPE, setting.getId(), LABEL)
				.details("Logo retiré des factures.")
				.before(Map.of("logo", before)));
		});
	}

	private InvoiceSetting current(boolean forUpdate) {
		return (forUpdate ? this.settings.findCurrentForUpdate() : this.settings.findCurrent())
			.orElseThrow(() -> new IllegalStateException("Configuration de la facture absente (migration V035)."));
	}

	private InvoiceSettingsResponse response(InvoiceSetting setting) {
		InvoiceSettings values = setting.values();
		LogoInfo logo = this.logos.findCurrent().map(InvoiceLogo::info).orElse(null);
		return new InvoiceSettingsResponse(values.company(), values.header(), values.footer(), values.appearance(), logo,
				setting.getUpdatedAt());
	}

	/** Image du logo telle qu'enregistrée. */
	public record StoredLogo(String contentType, byte[] data, String sha256) {
	}

}
