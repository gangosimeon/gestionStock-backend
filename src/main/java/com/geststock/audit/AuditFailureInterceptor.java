package com.geststock.audit;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.geststock.exception.ApiErrorFactory;
import com.geststock.exception.ErrorCode;

/**
 * Trace les échecs des points d'entrée {@link Audited} : toute réponse en erreur (4xx, 5xx)
 * donne une entrée {@code FAILURE} portant le code d'erreur de l'API. L'auteur est celui du
 * contexte de sécurité (requête authentifiée) ; une requête non authentifiée est rejetée avant
 * d'atteindre le contrôleur et n'est donc pas tracée ici.
 */
@Configuration
class AuditFailureInterceptor implements HandlerInterceptor, WebMvcConfigurer {

	private static final Logger log = LoggerFactory.getLogger(AuditFailureInterceptor.class);

	/** Absent dans les tests de la seule couche web ({@code @WebMvcTest}) : rien n'est alors tracé. */
	private final ObjectProvider<AuditService> audit;

	AuditFailureInterceptor(ObjectProvider<AuditService> audit) {
		this.audit = audit;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		// En premier : il doit voir aussi les refus du contrôle anticipé des permissions.
		registry.addInterceptor(this).addPathPatterns("/api/**").order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
	}

	@Override
	public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
			Exception ex) {
		AuditService service = this.audit.getIfAvailable();
		if (service == null || !(handler instanceof HandlerMethod method)) {
			return;
		}
		Audited audited = method.getMethodAnnotation(Audited.class);
		int status = response.getStatus();
		if (audited == null || (status < 400 && ex == null)) {
			return;
		}
		String errorCode = (String) request.getAttribute(ApiErrorFactory.ERROR_CODE_ATTRIBUTE);
		String message = (String) request.getAttribute(ApiErrorFactory.ERROR_MESSAGE_ATTRIBUTE);
		// Refus de droit : l'exception remonte à Spring Security, qui écrira le 403 après nous.
		if (ex instanceof AccessDeniedException) {
			status = ErrorCode.ACCESS_DENIED.status().value();
			errorCode = ErrorCode.ACCESS_DENIED.name();
			message = ErrorCode.ACCESS_DENIED.defaultMessage();
		}
		else if (ex instanceof AuthenticationException) {
			status = 401;
			errorCode = ErrorCode.UNAUTHENTICATED.name();
		}
		AuditEvent event = AuditEvent.of(audited.action(), audited.entityType(), pathId(request, audited), null)
			.details(message != null ? message : ex != null ? ex.getClass().getSimpleName() : null)
			.meta("path", request.getMethod() + " " + request.getRequestURI());
		if (audited.module().length > 0) {
			event.module(audited.module()[0]);
		}
		try {
			service.failure(event, errorCode, status >= 400 ? status : 500);
		}
		catch (RuntimeException failure) {
			// Le journal ne doit pas masquer l'erreur d'origine déjà renvoyée au client.
			log.error("Échec de l'écriture du journal d'audit ({} {})", audited.action(), audited.entityType(), failure);
		}
	}

	@SuppressWarnings("unchecked")
	private static String pathId(HttpServletRequest request, Audited audited) {
		if (audited.idParam().isEmpty()) {
			return null;
		}
		Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
		return variables instanceof Map<?, ?> map ? (String) ((Map<String, String>) map).get(audited.idParam()) : null;
	}

}
