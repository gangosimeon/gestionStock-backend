package com.geststock.security;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.geststock.common.security.HandlerPermissions;

/**
 * Contrôle des permissions <b>avant</b> la lecture et la validation du corps de la requête.
 *
 * <p>{@code @PreAuthorize} s'applique à l'appel de la méthode du contrôleur, donc après la
 * validation {@code @Valid} des arguments : sans ce contrôle anticipé, un utilisateur non
 * autorisé recevrait les erreurs de validation (400) au lieu d'un refus (403), et la tentative
 * serait mal qualifiée dans le journal d'audit. Mêmes règles que les annotations, qui restent
 * en place et font foi.
 */
@Configuration(proxyBeanMethods = false)
class PermissionPreCheck implements HandlerInterceptor, WebMvcConfigurer {

	/** Après l'intercepteur d'audit (qui doit voir le refus), avant toute autre logique. */
	static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(this).addPathPatterns("/api/**").order(ORDER);
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!(handler instanceof HandlerMethod method)) {
			return true;
		}
		List<String> required = HandlerPermissions.of(method);
		if (required == null || required.isEmpty()) {
			return true;
		}
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		boolean granted = authentication != null && authentication.getAuthorities().stream()
			.map(GrantedAuthority::getAuthority)
			.anyMatch(required::contains);
		if (!granted) {
			throw new AccessDeniedException("Permission manquante : " + String.join(" ou ", required));
		}
		return true;
	}

}
