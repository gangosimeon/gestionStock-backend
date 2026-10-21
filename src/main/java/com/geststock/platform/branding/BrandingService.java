package com.geststock.platform.branding;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.geststock.exception.ErrorCode;
import com.geststock.exception.ForbiddenOperationException;
import com.geststock.exception.InvalidRequestException;
import com.geststock.exception.NotFoundException;
import com.geststock.platform.PlatformDb;
import com.geststock.platform.audit.PlatformAudit;
import com.geststock.platform.branding.BrandingDtos.BrandingRequest;
import com.geststock.platform.branding.BrandingDtos.BrandingResponse;
import com.geststock.platform.branding.BrandingDtos.CompanyProfileResponse;
import com.geststock.platform.branding.BrandingDtos.PublicBrandingResponse;
import com.geststock.platform.branding.BrandingDtos.UpdateCompanyProfileRequest;
import com.geststock.platform.company.CompanyDirectory;
import com.geststock.settings.LogoImage;

/**
 * Identité visuelle et coordonnées des entreprises (base plateforme). Couleurs validées {@code #RRGGBB}
 * et normalisées en majuscules ; logo PNG ou JPEG vérifié (signature, dimensions, décodage complet),
 * 1 Mo au plus. Aucun CSS ni HTML libre n'est accepté.
 */
@Service
public class BrandingService {

	static final int LOGO_MAX_BYTES = 1024 * 1024;

	static final int LOGO_MAX_DIMENSION = 2000;

	private final PlatformDb db;

	private final PlatformAudit audit;

	private final CompanyDirectory directory;

	private final Clock clock;

	BrandingService(PlatformDb db, PlatformAudit audit, CompanyDirectory directory, Clock clock) {
		this.db = db;
		this.audit = audit;
		this.directory = directory;
		this.clock = clock;
	}

	public BrandingResponse get(UUID companyId) {
		return this.db.jdbc()
			.sql("""
					select c.code, c.trade_name, b.*, (b.logo is not null) as has_logo
					from platform.company c join platform.company_branding b on b.company_id = c.id
					where c.id = :id""")
			.param("id", companyId)
			.query(BrandingService::response)
			.optional()
			.orElseThrow(BrandingService::notFound);
	}

	/** Marque publique (écran de connexion) : entreprises actives ou suspendues uniquement. */
	public Optional<PublicBrandingResponse> publicBranding(String code) {
		if (code == null || !code.matches("[a-z0-9-]{1,40}")) {
			return Optional.empty();
		}
		return this.db.jdbc()
			.sql("""
					select c.code, c.trade_name, c.status, b.*, (b.logo is not null) as has_logo
					from platform.company c join platform.company_branding b on b.company_id = c.id
					where c.code = :code and c.status in ('ACTIVE', 'SUSPENDED')""")
			.param("code", code)
			.query((rs, n) -> {
				BrandingResponse b = response(rs, n);
				return new PublicBrandingResponse(b.companyCode(), b.companyName(), b.primaryColor(), b.secondaryColor(),
						b.accentColor(), b.sidebarColor(), b.headerColor(), b.buttonColor(), b.linkColor(), b.themeMode(),
						b.highContrast(), b.logoUrl(), rs.getString("status"));
			})
			.optional();
	}

	/** Logo public (servi avec son type vérifié), entreprises actives ou suspendues uniquement. */
	public Optional<Logo> publicLogo(String code) {
		if (code == null || !code.matches("[a-z0-9-]{1,40}")) {
			return Optional.empty();
		}
		return this.db.jdbc()
			.sql("""
					select b.logo, b.logo_content_type, b.logo_updated_at
					from platform.company c join platform.company_branding b on b.company_id = c.id
					where c.code = :code and c.status in ('ACTIVE', 'SUSPENDED') and b.logo is not null""")
			.param("code", code)
			.query((rs, n) -> new Logo(rs.getBytes("logo"), rs.getString("logo_content_type"),
					rs.getTimestamp("logo_updated_at").toInstant()))
			.optional();
	}

	public BrandingResponse update(UUID companyId, BrandingRequest request) {
		int updated = this.db.jdbc()
			.sql("""
					update platform.company_branding set primary_color = :primary, secondary_color = :secondary,
					    accent_color = :accent, sidebar_color = :sidebar, header_color = :header, button_color = :button,
					    link_color = :link, theme_mode = :mode, high_contrast = :contrast, footer_text = :footer,
					    version = version + 1, updated_at = :now
					where company_id = :id""")
			.param("primary", color(request.primaryColor()))
			.param("secondary", color(request.secondaryColor()))
			.param("accent", color(request.accentColor()))
			.param("sidebar", color(request.sidebarColor()))
			.param("header", color(request.headerColor()))
			.param("button", color(request.buttonColor()))
			.param("link", color(request.linkColor()))
			.param("mode", request.themeMode())
			.param("contrast", request.highContrast())
			.param("footer", request.footerText() != null && !request.footerText().isBlank() ? request.footerText().trim() : null)
			.param("now", Timestamp.from(Instant.now(this.clock)))
			.param("id", companyId)
			.update();
		if (updated == 0) {
			throw notFound();
		}
		this.audit.record("COMPANY_BRANDING_UPDATED", companyId, "Couleurs et mentions modifiées");
		return get(companyId);
	}

	public BrandingResponse setCompanyCanEdit(UUID companyId, boolean allowed) {
		int updated = this.db.jdbc()
			.sql("update platform.company_branding set company_can_edit = :allowed, updated_at = now() where company_id = :id")
			.param("allowed", allowed)
			.param("id", companyId)
			.update();
		if (updated == 0) {
			throw notFound();
		}
		this.audit.record("COMPANY_BRANDING_PERMISSION", companyId,
				allowed ? "Personnalisation ouverte à l'entreprise" : "Personnalisation réservée à GestStock");
		return get(companyId);
	}

	public BrandingResponse uploadLogo(UUID companyId, byte[] data) {
		if (data == null || data.length == 0 || data.length > LOGO_MAX_BYTES) {
			throw new InvalidRequestException(ErrorCode.BRANDING_INVALID_LOGO, ErrorCode.BRANDING_INVALID_LOGO.defaultMessage());
		}
		LogoImage image = LogoImage.of(data, LOGO_MAX_BYTES, LOGO_MAX_DIMENSION);
		int updated = this.db.jdbc()
			.sql("""
					update platform.company_branding set logo = :logo, logo_content_type = :type, logo_updated_at = :now,
					    version = version + 1, updated_at = :now
					where company_id = :id""")
			.param("logo", image.data())
			.param("type", image.contentType())
			.param("now", Timestamp.from(Instant.now(this.clock)))
			.param("id", companyId)
			.update();
		if (updated == 0) {
			throw notFound();
		}
		this.audit.record("COMPANY_LOGO_UPDATED", companyId,
				"Logo " + image.contentType() + " " + image.width() + "×" + image.height() + " (" + image.data().length + " octets)");
		return get(companyId);
	}

	public BrandingResponse deleteLogo(UUID companyId) {
		this.db.jdbc()
			.sql("update platform.company_branding set logo = null, logo_content_type = null, logo_updated_at = null, updated_at = now() where company_id = :id")
			.param("id", companyId)
			.update();
		this.audit.record("COMPANY_LOGO_DELETED", companyId, null);
		return get(companyId);
	}

	/** Contrôle côté serveur : l'entreprise ne modifie sa personnalisation que si GestStock l'y autorise. */
	public void requireCompanyCanEdit(UUID companyId) {
		if (!get(companyId).companyCanEdit()) {
			throw new ForbiddenOperationException(ErrorCode.BRANDING_NOT_EDITABLE, ErrorCode.BRANDING_NOT_EDITABLE.defaultMessage());
		}
	}

	public CompanyProfileResponse profile(UUID companyId) {
		BrandingResponse branding = get(companyId);
		return this.db.jdbc()
			.sql("select * from platform.company where id = :id")
			.param("id", companyId)
			.query((rs, n) -> new CompanyProfileResponse(rs.getString("code"), rs.getString("legal_name"),
					rs.getString("trade_name"), rs.getString("sector"), rs.getString("address"), rs.getString("city"),
					rs.getString("country"), rs.getString("phone"), rs.getString("email"), rs.getString("website"),
					rs.getString("tax_id"), rs.getString("currency"), rs.getString("plan"),
					rs.getDate("subscription_ends_on") != null ? rs.getDate("subscription_ends_on").toLocalDate() : null,
					branding))
			.single();
	}

	public CompanyProfileResponse updateProfile(UUID companyId, UpdateCompanyProfileRequest request) {
		this.db.jdbc()
			.sql("""
					update platform.company set trade_name = :tradeName, address = :address, city = :city, country = :country,
					    phone = :phone, email = :email, website = :website, version = version + 1, updated_at = now()
					where id = :id""")
			.param("tradeName", request.tradeName().trim())
			.param("address", trim(request.address()))
			.param("city", trim(request.city()))
			.param("country", trim(request.country()))
			.param("phone", trim(request.phone()))
			.param("email", trim(request.email()))
			.param("website", trim(request.website()))
			.param("id", companyId)
			.update();
		this.directory.invalidate(companyId);
		this.audit.record("COMPANY_PROFILE_UPDATED_BY_COMPANY", companyId, "Coordonnées modifiées par l'entreprise");
		return profile(companyId);
	}

	private static BrandingResponse response(ResultSet rs, int row) throws SQLException {
		Timestamp logoUpdated = rs.getTimestamp("logo_updated_at");
		String code = rs.getString("code");
		String logoUrl = rs.getBoolean("has_logo") && logoUpdated != null
				? "/api/public/companies/" + code + "/logo?v=" + logoUpdated.toInstant().toEpochMilli() : null;
		return new BrandingResponse(code, rs.getString("trade_name"), rs.getString("primary_color"),
				rs.getString("secondary_color"), rs.getString("accent_color"), rs.getString("sidebar_color"),
				rs.getString("header_color"), rs.getString("button_color"), rs.getString("link_color"),
				rs.getString("theme_mode"), rs.getBoolean("high_contrast"), rs.getString("footer_text"),
				rs.getBoolean("company_can_edit"), logoUrl, rs.getTimestamp("updated_at").toInstant());
	}

	private static String color(String value) {
		String normalized = value.trim().toUpperCase(Locale.ROOT);
		if (!normalized.matches("#[0-9A-F]{6}")) {
			throw new InvalidRequestException(ErrorCode.VALIDATION_FAILED, "Couleur invalide : " + value);
		}
		return normalized;
	}

	private static String trim(String value) {
		return value != null && !value.isBlank() ? value.trim() : null;
	}

	private static NotFoundException notFound() {
		return new NotFoundException(ErrorCode.COMPANY_NOT_FOUND, ErrorCode.COMPANY_NOT_FOUND.defaultMessage());
	}

	/** Logo servi publiquement. */
	public record Logo(byte[] data, String contentType, Instant updatedAt) {
	}

}
