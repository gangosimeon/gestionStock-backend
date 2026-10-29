package com.geststock.integration;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.geststock.platform.PlatformDb;
import com.geststock.tenancy.CurrentTenant;

/**
 * Devise de l'entreprise courante, en code ISO 4217 (v11-D6). Lue dans la <b>base plateforme</b>
 * ({@code platform.company.currency}) par son propre pool ({@link PlatformDb}), hors de toute transaction de
 * l'entreprise : l'instantané la lit avant d'ouvrir sa transaction de page, qui ne peut pas joindre une autre base.
 */
@Component
class CompanyCurrency {

	private static final String CURRENCY = "select currency from platform.company where id = :id";

	private final PlatformDb platform;

	private final CurrentTenant tenant;

	CompanyCurrency(PlatformDb platform, CurrentTenant tenant) {
		this.platform = platform;
		this.tenant = tenant;
	}

	/**
	 * @throws UnsupportedCurrencyException devise sans code ISO 4217 connu
	 */
	String isoCode() {
		UUID companyId = this.tenant.require().companyId();
		String stored = this.platform.jdbc().sql(CURRENCY).param("id", companyId).query(String.class).optional()
			.orElseThrow(() -> new IllegalStateException("Entreprise " + companyId + " absente de la plateforme"));
		return CurrencyCodes.iso4217(stored);
	}

}
