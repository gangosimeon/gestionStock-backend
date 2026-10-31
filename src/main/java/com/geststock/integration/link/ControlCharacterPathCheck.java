package com.geststock.integration.link;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Variables de chemin des routes d'intégration (lot 2, étape G15) : un caractère de contrôle est refusé
 * ({@code 400 VALIDATION_FAILED}) avant la conversion de l'argument. La conversion en {@code UUID} retire les
 * caractères de contrôle de début et de fin ({@code trim}) : sans ce contrôle, {@code <id>%00} désignerait la liaison
 * {@code <id>}.
 */
@Configuration(proxyBeanMethods = false)
class ControlCharacterPathCheck implements HandlerInterceptor, WebMvcConfigurer {

	/** Après l'audit des échecs et le contrôle anticipé des permissions (un refus de droits reste un 403). */
	static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(this)
			.addPathPatterns(IntegrationSecurityConfig.BASE_PATH + "/**", "/api/integration-links/**")
			.order(ORDER);
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?, ?> variables) {
			variables.forEach((name, value) -> {
				if (value instanceof String text && ControlCharacters.present(text, false)) {
					throw ControlCharacters.refused(String.valueOf(name));
				}
			});
		}
		return true;
	}

}
