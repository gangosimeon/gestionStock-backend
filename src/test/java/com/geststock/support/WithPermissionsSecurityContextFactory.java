package com.geststock.support;

import java.util.Arrays;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

import com.geststock.common.security.Permissions;

/** Contexte de sécurité de {@link WithPermissions}. */
public class WithPermissionsSecurityContextFactory implements WithSecurityContextFactory<WithPermissions> {

	@Override
	public SecurityContext createSecurityContext(WithPermissions annotation) {
		List<SimpleGrantedAuthority> authorities = (annotation.all() ? Permissions.ALL.stream()
				: Arrays.stream(annotation.value()))
			.map(SimpleGrantedAuthority::new)
			.toList();
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("test-actor", null, authorities));
		return context;
	}

	/** Exécute une action hors requête HTTP avec toutes les permissions (préparation de données). */
	public static <T> T runWithAllPermissions(java.util.function.Supplier<T> action) {
		SecurityContext previous = SecurityContextHolder.getContext();
		try {
			SecurityContext context = SecurityContextHolder.createEmptyContext();
			context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("test-setup", null,
					Permissions.ALL.stream().map(SimpleGrantedAuthority::new).toList()));
			SecurityContextHolder.setContext(context);
			return action.get();
		}
		finally {
			SecurityContextHolder.setContext(previous);
		}
	}

}
