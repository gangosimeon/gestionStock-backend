package com.geststock.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Origine d'une demande de session (conservée avec le refresh token pour l'investigation).
 * L'adresse IP tient compte des en-têtes de proxy si {@code server.forward-headers-strategy}
 * est configuré (profil prod).
 */
record ClientInfo(String userAgent, String ipAddress) {

	static ClientInfo from(HttpServletRequest request) {
		return new ClientInfo(truncate(request.getHeader("User-Agent"), 255), truncate(request.getRemoteAddr(), 45));
	}

	private static String truncate(String value, int max) {
		return value == null || value.length() <= max ? value : value.substring(0, max);
	}

}
