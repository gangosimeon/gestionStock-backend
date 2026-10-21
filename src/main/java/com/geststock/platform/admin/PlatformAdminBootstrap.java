package com.geststock.platform.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.geststock.platform.PlatformProperties;
import com.geststock.platform.audit.PlatformAudit;

/**
 * Premier Super Admin : créé au démarrage depuis {@code GESTSTOCK_PLATFORM_ADMIN_USERNAME} et
 * {@code GESTSTOCK_PLATFORM_ADMIN_PASSWORD}, uniquement s'il n'existe aucun Super Admin actif. Le mot de
 * passe doit respecter la politique renforcée et devra être changé à la première connexion (accès
 * restreint d'ici là). Sans ces variables, rien n'est créé : aucun identifiant par défaut n'existe.
 */
@Component
class PlatformAdminBootstrap implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(PlatformAdminBootstrap.class);

	private final PlatformAdminStore admins;

	private final PasswordEncoder passwordEncoder;

	private final PlatformProperties.BootstrapAdmin settings;

	private final PlatformAudit audit;

	PlatformAdminBootstrap(PlatformAdminStore admins, PasswordEncoder passwordEncoder, PlatformProperties properties,
			PlatformAudit audit) {
		this.admins = admins;
		this.passwordEncoder = passwordEncoder;
		this.settings = properties.bootstrapAdmin();
		this.audit = audit;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (this.admins.hasActiveAdmin()) {
			return;
		}
		if (!this.settings.isConfigured()) {
			log.warn("Aucun Super Admin actif : définir GESTSTOCK_PLATFORM_ADMIN_USERNAME et "
					+ "GESTSTOCK_PLATFORM_ADMIN_PASSWORD pour créer le premier (voir docs/securite-multi-entreprises.md).");
			return;
		}
		String username = this.settings.username().trim();
		if (this.admins.findByUsername(username).isPresent()) {
			log.warn("Le Super Admin « {} » existe mais est désactivé : réactivation manuelle requise.", username);
			return;
		}
		PlatformPasswordPolicy.check(this.settings.password(), username);
		var id = this.admins.create(username, this.settings.fullName(), this.passwordEncoder.encode(this.settings.password()));
		this.audit.record("PLATFORM_ADMIN_BOOTSTRAPPED", null, "Premier Super Admin créé", id, username);
		log.info("Super Admin initial « {} » créé (mot de passe à changer à la première connexion).", username);
	}

}
