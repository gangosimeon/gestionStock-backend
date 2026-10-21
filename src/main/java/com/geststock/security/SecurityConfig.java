package com.geststock.security;

import java.util.function.Supplier;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import com.geststock.common.security.RequiresPermission;
import org.springframework.security.core.annotation.AnnotationTemplateExpressionDefaults;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import com.geststock.platform.admin.PlatformAuthentication;

/**
 * Chaîne de sécurité HTTP de l'API.
 *
 * <ul>
 * <li>Sans état : aucune session serveur, aucun cookie d'authentification.</li>
 * <li>Authentification par jeton d'accès JWT ({@code Authorization: Bearer ...}), validé
 * par le filtre Bearer de Spring Security puis converti en {@link CurrentUser} par
 * {@link JwtUserAuthenticationConverter} (compte et droits relus en base).</li>
 * <li>Refus par défaut : tout ce qui n'est pas explicitement public exige une
 * authentification, et chaque endpoint métier exige en plus une permission
 * ({@code @PreAuthorize}). Aucune permission n'est accordée par défaut.</li>
 * <li>Aucun repli : sans jeton valide, la réponse est 401, jamais un accès « administrateur ».</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

	/** Endpoints d'authentification accessibles sans jeton. */
	static final String[] PUBLIC_AUTH_PATHS = {
			"/api/auth/login",
			"/api/auth/refresh",
			"/api/auth/logout",
			"/api/auth/forgot-password",
			"/api/auth/activate",
			"/api/platform/auth/login",
			"/api/platform/auth/refresh",
			"/api/platform/auth/logout"
	};

	/** Lectures publiques : marque d'une entreprise pour l'écran de connexion (aucune donnée métier). */
	static final String[] PUBLIC_GET_PATHS = { "/api/public/**" };

	/** Profil et changement de mot de passe : seules routes ouvertes au Super Admin qui doit changer son mot de passe. */
	static final String[] PLATFORM_ACCOUNT_PATHS = { "/api/platform/auth/me", "/api/platform/auth/change-password" };

	/** Autres routes accessibles sans authentification. */
	static final String[] PUBLIC_PATHS = {
			"/api/health",
			"/actuator/health",
			"/actuator/health/**",
			"/error",
			// Documentation : les routes n'existent que si springdoc est activé (désactivé en prod).
			"/v3/api-docs",
			"/v3/api-docs/**",
			"/swagger-ui.html",
			"/swagger-ui/**"
	};

	/** Active les annotations à gabarit comme {@link RequiresPermission}. */
	@Bean
	static AnnotationTemplateExpressionDefaults annotationTemplateExpressionDefaults() {
		return new AnnotationTemplateExpressionDefaults();
	}

	@Bean
	SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, RestAuthenticationEntryPoint authenticationEntryPoint,
			RestAccessDeniedHandler accessDeniedHandler, JwtDecoder jwtDecoder,
			JwtAuthenticationResolver jwtAuthenticationResolver) throws Exception {
		http
			.csrf(AbstractHttpConfigurer::disable)
			.cors(Customizer.withDefaults())
			.httpBasic(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.requestCache(AbstractHttpConfigurer::disable)
			.anonymous(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.exceptionHandling(exceptions -> exceptions
				.authenticationEntryPoint(authenticationEntryPoint)
				.accessDeniedHandler(accessDeniedHandler))
			.oauth2ResourceServer(resourceServer -> resourceServer
				.bearerTokenResolver(bearerTokenResolver())
				.authenticationEntryPoint(authenticationEntryPoint)
				.accessDeniedHandler(accessDeniedHandler)
				.jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(jwtAuthenticationResolver::resolve)))
			.authorizeHttpRequests(authorize -> authorize
				.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
				.requestMatchers(HttpMethod.POST, PUBLIC_AUTH_PATHS).permitAll()
				.requestMatchers(HttpMethod.GET, PUBLIC_GET_PATHS).permitAll()
				.requestMatchers(PUBLIC_PATHS).permitAll()
				// Espace Super Admin : jetons plateforme uniquement.
				.requestMatchers(PLATFORM_ACCOUNT_PATHS)
				.hasAnyRole(PlatformAuthentication.ROLE, PlatformAuthentication.PASSWORD_CHANGE_ROLE)
				.requestMatchers("/api/platform/**").hasRole(PlatformAuthentication.ROLE)
				// Tout le reste est métier : fermé aux jetons plateforme (aucune entreprise courante).
				.anyRequest().access(SecurityConfig::companyUser));
		return http.build();
	}

	/** Utilisateur d'entreprise authentifié : tout jeton sauf un jeton Super Admin. */
	static AuthorizationDecision companyUser(Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) {
		Authentication auth = authentication.get();
		boolean granted = auth != null && auth.isAuthenticated() && !(auth instanceof PlatformAuthentication)
				&& auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
					.noneMatch(a -> a.equals(PlatformAuthentication.AUTHORITY) || a.equals(PlatformAuthentication.PASSWORD_CHANGE_AUTHORITY));
		return new AuthorizationDecision(granted);
	}

	/**
	 * Jeton lu uniquement dans l'en-tête {@code Authorization} (jamais dans l'URL), et ignoré
	 * sur les endpoints publics d'authentification : le front y joint parfois un jeton expiré
	 * (renouvellement de session), qui ne doit pas faire échouer la requête.
	 */
	static BearerTokenResolver bearerTokenResolver() {
		DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
		delegate.setAllowUriQueryParameter(false);
		delegate.setAllowFormEncodedBodyParameter(false);
		RequestMatcher publicAuth = new OrRequestMatcher(java.util.Arrays.stream(PUBLIC_AUTH_PATHS)
			.map(path -> (RequestMatcher) PathPatternRequestMatcher.pathPattern(HttpMethod.POST, path))
			.toList());
		return (HttpServletRequest request) -> publicAuth.matches(request) ? null : delegate.resolve(request);
	}

}
