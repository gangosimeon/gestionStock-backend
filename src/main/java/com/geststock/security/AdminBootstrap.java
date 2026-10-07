package com.geststock.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.geststock.user.UserAccountService;

/**
 * Premier démarrage : s'il n'existe aucun administrateur actif, en crée un à partir de
 * {@code GESTSTOCK_ADMIN_USERNAME} et {@code GESTSTOCK_ADMIN_PASSWORD} (mot de passe
 * temporaire, à changer). Sans ces variables, aucun compte n'est créé : il n'existe ni
 * identifiant ni mot de passe par défaut.
 */
@Component
class AdminBootstrap implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

	private final UserAccountService accounts;

	private final GestStockSecurityProperties.BootstrapAdmin settings;

	AdminBootstrap(UserAccountService accounts, GestStockSecurityProperties properties) {
		this.accounts = accounts;
		this.settings = properties.bootstrapAdmin();
	}

	@Override
	public void run(ApplicationArguments args) {
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
