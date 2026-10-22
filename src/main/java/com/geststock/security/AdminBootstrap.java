package com.geststock.security;

import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.geststock.platform.PlatformProperties;
import com.geststock.tenancy.DefaultTenant;
import com.geststock.tenancy.TenantContext;
import com.geststock.tenancy.TenantDirectory;
import com.geststock.tenancy.TenantRef;
import com.geststock.user.UserAccountService;

/**
 * Premier démarrage de la base historique : s'il n'existe aucun administrateur actif, en crée un à
 * partir de {@code GESTSTOCK_ADMIN_USERNAME} et {@code GESTSTOCK_ADMIN_PASSWORD} (mot de passe
 * temporaire, à changer). Sans ces variables, aucun compte n'est créé : il n'existe ni identifiant ni
 * mot de passe par défaut.
 *
 * <p>Base visée explicitement (aucune entreprise courante au démarrage) : l'entreprise historique
 * ({@code GESTSTOCK_LEGACY_COMPANY_CODE}), sinon l'entreprise par défaut (dev/test). Sans l'une ni
 * l'autre, rien à faire : l'administrateur des entreprises créées vient du provisionnement.
 */
@Component
class AdminBootstrap implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

	private final UserAccountService accounts;

	private final GestStockSecurityProperties.BootstrapAdmin settings;

	private final PlatformProperties.LegacyCompany legacyCompany;

	private final TenantDirectory directory;

	private final DefaultTenant defaultTenant;

	AdminBootstrap(UserAccountService accounts, GestStockSecurityProperties properties, PlatformProperties platform,
			TenantDirectory directory, DefaultTenant defaultTenant) {
		this.accounts = accounts;
		this.settings = properties.bootstrapAdmin();
		this.legacyCompany = platform.legacyCompany();
		this.directory = directory;
		this.defaultTenant = defaultTenant;
	}

	@Override
	public void run(ApplicationArguments args) {
		Optional<TenantRef> target = target();
		if (target.isEmpty()) {
			return;
		}
		TenantContext.runAs(target.get(), this::bootstrap);
	}

	/** Base historique active, sinon entreprise par défaut (dev/test). */
	Optional<TenantRef> target() {
		if (this.legacyCompany.isConfigured()) {
			return this.directory.findByCode(this.legacyCompany.code().trim().toLowerCase(Locale.ROOT))
				.filter(TenantDirectory.TenantStatus::isActive)
				.map(TenantDirectory.TenantStatus::ref);
		}
		return this.defaultTenant.get();
	}

	private void bootstrap() {
		if (this.accounts.hasActiveAdministrator()) {
			return;
		}
		if (!this.settings.isConfigured()) {
			log.warn("Aucun administrateur actif et aucun administrateur initial configuré : personne ne peut "
					+ "administrer l'application. Définir GESTSTOCK_ADMIN_USERNAME et GESTSTOCK_ADMIN_PASSWORD.");
			return;
		}
		this.accounts.createInitialAdministrator(this.settings.username().trim(), this.settings.lastName(),
				this.settings.password());
		log.info("Administrateur initial « {} » créé (mot de passe temporaire, à changer à la première connexion).",
				this.settings.username().trim());
	}

}
