package com.geststock.integration.link;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.geststock.exception.ApiError;
import com.geststock.exception.ApiErrorFactory;
import com.geststock.exception.ErrorCode;

import tools.jackson.databind.json.JsonMapper;

/**
 * Corps JSON illisible sur les routes d'intégration et les écrans de liaison (lot 2, étape G15) : même réponse que le
 * gestionnaire commun ({@code 400 MALFORMED_REQUEST}, format {@code ApiError}), écrite ici avant tout autre résolveur.
 * Le message de Jackson cite la valeur ou le jeton reçu (UUID illisible, jeton invalide…) : le résolveur commun des
 * {@code @ExceptionHandler} le journaliserait en DEBUG ({@code Resolved [...]}) et l'appel du gestionnaire en TRACE
 * (arguments). Ce résolveur ne journalise rien. La trace de la lecture elle-même ({@code Could not resolve parameter},
 * DEBUG) est coupée par le niveau du logger {@code org.springframework.web.method.HandlerMethod} ({@code application.yml}).
 */
@Configuration(proxyBeanMethods = false)
class UnreadableBodyResolver implements HandlerExceptionResolver, WebMvcConfigurer {

	private static final Set<Class<?>> CONTROLLERS = Set.of(IntegrationLinkController.class, LinkController.class);

	private final ApiErrorFactory errors;

	private final JsonMapper json;

	UnreadableBodyResolver(ApiErrorFactory errors, JsonMapper json) {
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
		if (!(ex instanceof HttpMessageNotReadableException) || !(handler instanceof HandlerMethod method)
				|| !CONTROLLERS.contains(method.getBeanType())) {
			return null;
		}
		try {
			// Comme le gestionnaire commun : statut, application/problem+json (sans paramètre charset), corps ApiError.
			ApiError error = this.errors.create(ErrorCode.MALFORMED_REQUEST, null, request);
			response.setStatus(error.status());
			response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
			this.json.writeValue(response.getOutputStream(), error);
		}
		catch (IOException failure) {
			// Réponse non écrite : le résolveur commun prend le relais.
			return null;
		}
		return new ModelAndView();
	}

}
