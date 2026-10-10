package com.geststock.common.security;

import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;

/**
 * Permissions exigées par un point d'entrée, lues sur ses annotations de sécurité :
 * {@link RequiresPermission} ou {@code @PreAuthorize("hasAnyAuthority('a', 'b')")}, de la
 * méthode sinon de la classe (même règle que Spring Security). Source unique pour le contrôle
 * anticipé des droits et pour la documentation OpenAPI.
 */
public final class HandlerPermissions {

	private static final Pattern AUTHORITY = Pattern.compile("'([a-z]+:[a-z]+)'");

	private HandlerPermissions() {
	}

	/**
	 * @return l'une de ces permissions suffit ; liste vide : tout utilisateur connecté ;
	 *         {@code null} : aucune règle de méthode (route publique ou hors API)
	 */
	public static List<String> of(HandlerMethod handler) {
		List<String> fromMethod = of(handler.getMethod());
		return fromMethod != null ? fromMethod : of(handler.getBeanType());
	}

	private static List<String> of(AnnotatedElement element) {
		RequiresPermission required = element.getAnnotation(RequiresPermission.class);
		if (required != null) {
			return List.of(required.value());
		}
		PreAuthorize pre = element.getAnnotation(PreAuthorize.class);
		if (pre == null || AnnotatedElementUtils.isAnnotated(element, RequiresPermission.class)) {
			return null;
		}
		List<String> codes = new ArrayList<>();
		Matcher matcher = AUTHORITY.matcher(pre.value());
		while (matcher.find()) {
			codes.add(matcher.group(1));
		}
		return codes;
	}

}
