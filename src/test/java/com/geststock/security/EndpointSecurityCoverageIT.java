package com.geststock.security;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.geststock.integration.link.IntegrationSecurityConfig;
import com.geststock.integration.link.RequiresScope;
import com.geststock.support.PostgresTestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garde-fou de « refus par défaut » : tout endpoint {@code /api/**} est soit explicitement
 * public, soit protégé par {@code @PreAuthorize}. Un nouveau contrôleur qui oublie sa
 * permission fait échouer le build.
 *
 * <p>Intégration (lot 2, étape G6) : sous {@code /api/integration/v1/**}, chaque endpoint porte
 * {@link RequiresScope} ou figure dans la liste explicite des routes authentifiées par le client
 * ({@code oauth/token}, {@code oauth/par}) ; {@code @PreAuthorize} de la chaîne principale y est interdit
 * (aucun mélange entre jetons utilisateurs et jetons de liaison). G14 : la portée {@code ANY} (routes de la liaison
 * elle-même) et l'accès pendant une suspension sont deux règles distinctes ({@code POST /link/secret} : toute portée,
 * mais refusée pendant une suspension).
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

	/** Routes d'intégration authentifiées par le client (HTTP Basic, étape G8), sans portée. */
	private static final Set<String> CLIENT_AUTHENTICATED_ENDPOINTS = Set.of(
			"POST /api/integration/v1/oauth/token",
			"POST /api/integration/v1/oauth/par");

	private static final String INTEGRATION_PREFIX = "/api/integration/v1/";

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping handlerMapping;

	@Test
	void everyApiEndpointIsEitherPublicOrGuardedByAPermission() {
		Map<RequestMappingInfo, HandlerMethod> handlers = this.handlerMapping.getHandlerMethods();
		long apiEndpoints = handlers.keySet().stream().flatMap(info -> info.getPatternValues().stream())
			.filter(path -> path.startsWith("/api/")).count();

		assertThat(apiEndpoints).as("endpoints /api détectés").isGreaterThan(PUBLIC_ENDPOINTS.size());
		assertThat(violations(handlers)).as("endpoints sans protection déclarée").isEmpty();
	}

	@Test
	void publicEndpointsMatchTheSecurityConfiguration() {
		for (String path : SecurityConfig.PUBLIC_AUTH_PATHS) {
			assertThat(PUBLIC_ENDPOINTS).contains("POST " + path);
		}
		assertThat(CLIENT_AUTHENTICATED_ENDPOINTS).containsExactlyInAnyOrderElementsOf(
				List.of(IntegrationSecurityConfig.CLIENT_AUTHENTICATED_PATHS).stream().map(path -> "POST " + path).toList());
	}

	/**
	 * Cas négatifs explicites : la vérification signale une route d'intégration sans portée et une route d'intégration
	 * protégée par {@code @PreAuthorize}, et accepte une route avec portée.
	 */
	@Test
	void aMisconfiguredIntegrationControllerIsDetected() throws Exception {
		Map<RequestMappingInfo, HandlerMethod> handlers = new LinkedHashMap<>();
		MisconfiguredIntegrationController controller = new MisconfiguredIntegrationController();
		for (String name : List.of("unscoped", "mixed", "scoped", "servedWhileSuspended", "anyScope", "platformOnlyElsewhere")) {
			Method method = MisconfiguredIntegrationController.class.getDeclaredMethod(name);
			handlers.put(RequestMappingInfo.paths(INTEGRATION_PREFIX + "test-coverage/" + name).methods(RequestMethod.GET).build(),
					new HandlerMethod(controller, method));
		}
		// Route réellement autorisée pendant une suspension (v14-D10) : acceptée.
		handlers.put(RequestMappingInfo.paths(INTEGRATION_PREFIX + "link").methods(RequestMethod.GET).build(),
				new HandlerMethod(controller, MisconfiguredIntegrationController.class.getDeclaredMethod("linkStatus")));
		// Route réellement limitée à la base plateforme (C2) : acceptée.
		handlers.put(RequestMappingInfo.paths(INTEGRATION_PREFIX + "link/revoke").methods(RequestMethod.POST).build(),
				new HandlerMethod(controller, MisconfiguredIntegrationController.class.getDeclaredMethod("linkRevoke")));
		// Rotation du secret (G14) : toute portée, refusée pendant une suspension : acceptée.
		handlers.put(RequestMappingInfo.paths(INTEGRATION_PREFIX + "link/secret").methods(RequestMethod.POST).build(),
				new HandlerMethod(controller, MisconfiguredIntegrationController.class.getDeclaredMethod("linkSecret")));
		// La même, servie pendant une suspension : refusé.
		handlers.put(RequestMappingInfo.paths(INTEGRATION_PREFIX + "link/secret").methods(RequestMethod.PUT).build(),
				new HandlerMethod(controller, MisconfiguredIntegrationController.class.getDeclaredMethod("linkStatus")));
		// platformOnly sur GET /link : refusé (GET /link garde les contrôles d'entreprise).
		handlers.put(RequestMappingInfo.paths(INTEGRATION_PREFIX + "link").methods(RequestMethod.HEAD).build(),
				new HandlerMethod(controller, MisconfiguredIntegrationController.class.getDeclaredMethod("linkRevoke")));

		assertThat(violations(handlers)).containsExactlyInAnyOrder(
				"GET /api/integration/v1/test-coverage/unscoped : route d'intégration sans @RequiresScope",
				"GET /api/integration/v1/test-coverage/mixed : @PreAuthorize interdit sur une route d'intégration",
				"GET /api/integration/v1/test-coverage/servedWhileSuspended : allowedWhileSuspended hors des routes autorisées pendant une suspension",
				"GET /api/integration/v1/test-coverage/anyScope : portée ANY hors des routes de la liaison elle-même",
				"GET /api/integration/v1/test-coverage/platformOnlyElsewhere : platformOnly réservé à POST /link/revoke",
				"HEAD /api/integration/v1/link : allowedWhileSuspended hors des routes autorisées pendant une suspension",
				"HEAD /api/integration/v1/link : portée ANY hors des routes de la liaison elle-même",
				"HEAD /api/integration/v1/link : platformOnly réservé à POST /link/revoke",
				"PUT /api/integration/v1/link/secret : allowedWhileSuspended hors des routes autorisées pendant une suspension",
				"PUT /api/integration/v1/link/secret : portée ANY hors des routes de la liaison elle-même");
	}

	@Test
	void onlyLinkRevocationRunsWithoutCompanyContext() {
		assertThat(IntegrationSecurityConfig.PLATFORM_ONLY_ENDPOINTS).containsExactly("POST /api/integration/v1/link/revoke");
	}

	@Test
	void theRoutesAllowedDuringASuspensionAreExactlyThoseOfTheContract() {
		assertThat(IntegrationSecurityConfig.SUSPENSION_ALLOWED_ENDPOINTS)
			.containsExactlyInAnyOrder("GET /api/integration/v1/link", "POST /api/integration/v1/link/revoke");
	}

	@Test
	void theRoutesWithoutAParticularScopeAreExactlyThoseOfTheLinkItself() {
		assertThat(IntegrationSecurityConfig.ANY_SCOPE_ENDPOINTS).containsExactlyInAnyOrder("GET /api/integration/v1/link",
				"POST /api/integration/v1/link/revoke", "POST /api/integration/v1/link/secret");
	}

	/** Endpoints {@code /api/**} non protégés selon les règles ci-dessus (liste vide : configuration acceptée). */
	static List<String> violations(Map<RequestMappingInfo, HandlerMethod> handlers) {
		List<String> violations = new ArrayList<>();
		for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlers.entrySet()) {
			HandlerMethod handler = entry.getValue();
			boolean preAuthorized = AnnotatedElementUtils.hasAnnotation(handler.getMethod(), PreAuthorize.class)
					|| AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), PreAuthorize.class);
			RequiresScope requiresScope = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), RequiresScope.class);
			boolean scoped = requiresScope != null;
			boolean servedWhileSuspended = scoped && requiresScope.allowedWhileSuspended();
			boolean anyScope = scoped && RequiresScope.ANY.equals(requiresScope.value());
			boolean platformOnly = scoped && requiresScope.platformOnly();
			for (String path : entry.getKey().getPatternValues()) {
				if (!path.startsWith("/api/")) {
					continue;
				}
				for (var method : entry.getKey().getMethodsCondition().getMethods()) {
					String endpoint = method + " " + path;
					if (path.startsWith(INTEGRATION_PREFIX)) {
						if (preAuthorized) {
							violations.add(endpoint + " : @PreAuthorize interdit sur une route d'intégration");
						}
						else if (!scoped && !CLIENT_AUTHENTICATED_ENDPOINTS.contains(endpoint)) {
							violations.add(endpoint + " : route d'intégration sans @RequiresScope");
						}
						else {
							if (servedWhileSuspended && !IntegrationSecurityConfig.SUSPENSION_ALLOWED_ENDPOINTS.contains(endpoint)) {
								violations.add(endpoint + " : allowedWhileSuspended hors des routes autorisées pendant une suspension");
							}
							if (anyScope && !IntegrationSecurityConfig.ANY_SCOPE_ENDPOINTS.contains(endpoint)) {
								violations.add(endpoint + " : portée ANY hors des routes de la liaison elle-même");
							}
							if (platformOnly && !IntegrationSecurityConfig.PLATFORM_ONLY_ENDPOINTS.contains(endpoint)) {
								violations.add(endpoint + " : platformOnly réservé à POST /link/revoke");
							}
						}
					}
					else if (!preAuthorized && !PUBLIC_ENDPOINTS.contains(endpoint)) {
						violations.add(endpoint + " : sans @PreAuthorize ni déclaration publique");
					}
				}
			}
		}
		return violations;
	}

	/** Contrôleur jamais chargé dans un contexte : seulement lu par {@link #violations}. */
	static class MisconfiguredIntegrationController {

		void unscoped() {
		}

		@RequiresScope("catalog:read")
		@PreAuthorize("permitAll()")
		void mixed() {
		}

		@RequiresScope("catalog:read")
		void scoped() {
		}

		@RequiresScope(value = "catalog:read", allowedWhileSuspended = true)
		void servedWhileSuspended() {
		}

		@RequiresScope(RequiresScope.ANY)
		void anyScope() {
		}

		@RequiresScope(value = RequiresScope.ANY, allowedWhileSuspended = true)
		void linkStatus() {
		}

		@RequiresScope(value = RequiresScope.ANY, allowedWhileSuspended = true, platformOnly = true)
		void linkRevoke() {
		}

		@RequiresScope(value = "catalog:read", platformOnly = true)
		void platformOnlyElsewhere() {
		}

		@RequiresScope(RequiresScope.ANY)
		void linkSecret() {
		}

	}

}
