package com.geststock.integration.link;

import org.springframework.security.core.AuthenticationException;

/** Jeton valide d'une liaison révoquée : réponse {@code 401 LINK_REVOKED} (contrat, C2). */
class LinkRevokedException extends AuthenticationException {

	LinkRevokedException() {
		super("Liaison révoquée.");
	}

}
