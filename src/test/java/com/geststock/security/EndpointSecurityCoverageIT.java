package com.geststock.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.geststock.support.PostgresTestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garde-fou de « refus par défaut » : tout endpoint {@code /api/**} est soit explicitement
 * public, soit protégé par {@code @PreAuthorize}. Un nouveau contrôleur qui oublie sa
 * permission fait échouer le build.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class EndpointSecurityCoverageIT {

	private static final Set<String> PUBLIC_ENDPOINTS = Set.of(
			"POST /api/auth/login",
			"POST /api/auth/refresh",
			"POST /api/auth/logout",
			"POST /api/auth/forgot-password",
			"POST /api/auth/activate",
			"POST /api/platform/auth/login",
			"POST /api/platform/auth/refresh",
			"POST /api/platform/auth/logout",
			"GET /api/public/companies/{code}/branding",
			"GET /api/public/companies/{code}/logo",
			"GET /api/health");

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping handlerMapping;

	@Test
	void everyApiEndpointIsEitherPublicOrGuardedByAPermission() {
		List<String> unguarded = new ArrayList<>();
		int apiEndpoints = 0;
		for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : this.handlerMapping.getHandlerMethods().entrySet()) {
			for (String path : entry.getKey().getPatternValues()) {
				if (!path.startsWith("/api/")) {
					continue;
				}
				for (var method : entry.getKey().getMethodsCondition().getMethods()) {
					apiEndpoints++;
					String endpoint = method + " " + path;
					HandlerMethod handler = entry.getValue();
					boolean guarded = AnnotatedElementUtils.hasAnnotation(handler.getMethod(), PreAuthorize.class)
							|| AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), PreAuthorize.class);
					if (!guarded && !PUBLIC_ENDPOINTS.contains(endpoint)) {
						unguarded.add(endpoint);
					}
				}
			}
		}
		assertThat(apiEndpoints).as("endpoints /api détectés").isGreaterThan(PUBLIC_ENDPOINTS.size());
		assertThat(unguarded).as("endpoints sans @PreAuthorize ni déclaration publique").isEmpty();
	}

	@Test
	void publicEndpointsMatchTheSecurityConfiguration() {
		for (String path : SecurityConfig.PUBLIC_AUTH_PATHS) {
			assertThat(PUBLIC_ENDPOINTS).contains("POST " + path);
		}
	}

}
