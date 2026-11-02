package com.geststock.security;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.firewall.RequestRejectedHandler;

/**
 * Requête refusée par le pare-feu HTTP de Spring Security ({@code StrictHttpFirewall}) avant toute route : {@code 400}
 * sans corps (v15-D2), comme le gestionnaire par défaut, mais sans journal ni page d'erreur. Le gestionnaire par défaut
 * écrit en DEBUG le message de l'exception, qui cite la valeur refusée (nom de paramètre, en-tête…), puis renvoie la
 * requête vers {@code /error}, où le pare-feu la refuse de nouveau et {@code DispatcherServlet} journalise l'échec.
 * Le refus pendant la lecture des arguments d'une route est traité par {@code GlobalExceptionHandler}.
 */
@Configuration(proxyBeanMethods = false)
class RequestRejectedConfig {

	@Bean
	RequestRejectedHandler requestRejectedHandler() {
		return (request, response, ex) -> {
			if (!response.isCommitted()) {
				response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			}
		};
	}

}
