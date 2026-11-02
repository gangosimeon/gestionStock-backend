package com.geststock.exception;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import tools.jackson.databind.json.JsonMapper;

/**
 * Paramètre refusé par le pare-feu de Spring Security pendant la lecture des arguments d'une route (nom de paramètre
 * avec un caractère de contrôle, lu par la liaison d'un objet de filtre…) : {@code 400 INVALID_PARAMETER}
 * « Paramètre refusé. », format {@code ApiError}, écrit ici avant tout autre résolveur et sans aucun journal. Le message
 * de l'exception cite le nom ou la valeur refusés : le gestionnaire commun le journaliserait en ERROR (erreur
 * inattendue, 500) et le résolveur des {@code @ExceptionHandler} en DEBUG ({@code Resolved [...]}). Si l'écriture
 * échoue, {@code GlobalExceptionHandler} répond de même, sans journal.
 */
@Configuration(proxyBeanMethods = false)
class RequestRejectedResolver implements HandlerExceptionResolver, WebMvcConfigurer {

	static final String MESSAGE = "Paramètre refusé.";

	private final ApiErrorFactory errors;

	private final JsonMapper json;

	RequestRejectedResolver(ApiErrorFactory errors, JsonMapper json) {
		this.errors = errors;
		this.json = json;
	}

	@Override
	public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
		resolvers.add(0, this);
	}

	@Override
	public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response, Object handler,
			Exception ex) {
		if (!(ex instanceof RequestRejectedException)) {
			return null;
		}
		try {
			// Comme le gestionnaire commun : statut, application/problem+json (sans paramètre charset), corps ApiError.
			ApiError error = this.errors.create(ErrorCode.INVALID_PARAMETER, MESSAGE, request);
			response.setStatus(error.status());
			response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
			this.json.writeValue(response.getOutputStream(), error);
		}
		catch (IOException failure) {
			// Réponse non écrite : le gestionnaire commun prend le relais.
			return null;
		}
		return new ModelAndView();
	}

}
