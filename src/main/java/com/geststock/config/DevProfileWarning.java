package com.geststock.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Le profil {@code dev} est le profil par défaut : un serveur démarré sans
 * {@code SPRING_PROFILES_ACTIVE=prod} charge les données de démonstration et accepte une clé JWT
 * éphémère. L'avertissement le rend visible dans les journaux.
 */
@Component
@Profile("dev")
class DevProfileWarning {

	private static final Logger log = LoggerFactory.getLogger(DevProfileWarning.class);

	@EventListener(ApplicationReadyEvent.class)
	void warn() {
		log.warn("Profil DEV actif (données de démonstration, CORS localhost) : ne jamais l'utiliser en production. "
				+ "Serveur : SPRING_PROFILES_ACTIVE=prod.");
	}

}
