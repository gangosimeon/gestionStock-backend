package com.geststock.integration.link;

import java.io.IOException;
import java.util.Arrays;
import java.util.function.Supplier;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.RequestPath;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

import com.geststock.exception.ApiErrorFactory;
import com.geststock.exception.ApiErrorWriter;
import com.geststock.exception.ErrorCode;

/**
 * Chaîne de sécurité de l'intégration boutique (lot 2, étape G6 ; v13-D6, v14-D4).
 *
 * <ul>
 * <li>Limitée à {@code /api/integration/v1/**}, placée avant la chaîne principale ({@code SecurityConfig}, inchangée) :
 * aucune requête d'intégration n'atteint la chaîne principale.</li>
 * <li>Sans état, sans session, sans CSRF (aucun cookie), sans CORS (aucune origine navigateur : aucun en-tête
 * {@code Access-Control-*}), {@code Cache-Control: no-store} sur toutes les réponses.</li>
 * <li>Authentification : {@code Authorization: Bearer} vérifié par {@link IntegrationTokenService} (aucun bean
 * {@code JwtDecoder}) ; le principal est un {@link LinkPrincipal}. L'entreprise courante n'est pas établie ici
 * (étape G7).</li>
 * <li>Autorisation : seules les méthodes de contrôleur portant {@link RequiresScope} sont servies, et seulement si le
 * jeton porte la portée ; les routes du client ({@code oauth/token}, {@code oauth/par}) sont déclarées ici et refusées
 * jusqu'à l'étape G8 ; tout le reste est refusé.</li>
 * <li>Liaisons désactivées ({@code geststock.integration.links.enabled=false}) : 404 sur toute la chaîne, comme une
 * route inconnue.</li>
 * </ul>
 * Réponses : {@code 401 INVALID_TOKEN} avec {@code WWW-Authenticate: Bearer error="invalid_token"} ;
 * {@code 403 INSUFFICIENT_SCOPE} avec {@code WWW-Authenticate: Bearer error="insufficient_scope"} ;
 * {@code 403 ACCESS_DENIED} pour une route sans portée déclarée. La raison d'un refus de jeton n'est jamais renvoyée :
 * elle est journalisée, sans le jeton.
 */
@Configuration(proxyBeanMethods = false)
public class IntegrationSecurityConfig {

	private static final Logger log = LoggerFactory.getLogger(IntegrationSecurityConfig.class);

	public static final String BASE_PATH = "/api/integration/v1";

	/** Routes authentifiées par le client JeCréeMaBoutique (HTTP Basic, étape G8), jamais par un jeton de liaison. */
	public static final String[] CLIENT_AUTHENTICATED_PATHS = { BASE_PATH + "/oauth/token", BASE_PATH + "/oauth/par" };

	static final String INVALID_TOKEN_CHALLENGE = "Bearer error=\"invalid_token\"";

	static final String INSUFFICIENT_SCOPE_CHALLENGE = "Bearer error=\"insufficient_scope\"";

	private static final String REQUIRED_SCOPE_ATTRIBUTE = IntegrationSecurityConfig.class.getName() + ".REQUIRED_SCOPE";

	private static final String INSUFFICIENT_SCOPE_ATTRIBUTE = IntegrationSecurityConfig.class.getName() + ".INSUFFICIENT_SCOPE";

	private static final String NO_SCOPE = "";

	@Bean
	@Order(Ordered.HIGHEST_PRECEDENCE)
	SecurityFilterChain integrationSecurityFilterChain(HttpSecurity http, IntegrationLinkProperties links,
			IntegrationTokenService tokens, ApiErrorFactory errors, ApiErrorWriter writer,
			@Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> handlerMapping)
			throws Exception {
		ScopedEndpoints endpoints = new ScopedEndpoints(handlerMapping);
		AuthenticationEntryPoint entryPoint = (request, response, ex) -> unauthorized(request, response, errors, writer);
		AccessDeniedHandler accessDenied = (request, response, ex) -> denied(request, response, errors, writer);
		http
			.securityMatcher(BASE_PATH + "/**")
			.csrf(AbstractHttpConfigurer::disable)
			.cors(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.requestCache(AbstractHttpConfigurer::disable)
			.anonymous(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDenied))
			.oauth2ResourceServer(resourceServer -> resourceServer
				.bearerTokenResolver(bearerTokenResolver())
				.authenticationManagerResolver(request -> linkTokenAuthentication(tokens))
				.authenticationEntryPoint(entryPoint)
				.accessDeniedHandler(accessDenied))
			.authorizeHttpRequests(authorize -> authorize
				// Étape G8 : authentification Basic du client ; refusées tant qu'elles ne sont pas servies.
				.requestMatchers(CLIENT_AUTHENTICATED_PATHS).denyAll()
				.requestMatchers(endpoints::isScoped).access(endpoints::authorize)
				.anyRequest().denyAll())
			.addFilterAfter(new DisabledLinksFilter(links.enabled(), errors, writer), HeaderWriterFilter.class);
		return http.build();
	}

	/** Jeton lu dans l'en-tête {@code Authorization} seulement, et ignoré sur les routes du client. */
	static BearerTokenResolver bearerTokenResolver() {
		DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
		delegate.setAllowUriQueryParameter(false);
		delegate.setAllowFormEncodedBodyParameter(false);
		RequestMatcher clientPaths = new OrRequestMatcher(Arrays.stream(CLIENT_AUTHENTICATED_PATHS)
			.map(path -> (RequestMatcher) PathPatternRequestMatcher.pathPattern(path))
			.toList());
		return request -> clientPaths.matches(request) ? null : delegate.resolve(request);
	}

	private static AuthenticationManager linkTokenAuthentication(IntegrationTokenService tokens) {
		return authentication -> {
			if (!(authentication instanceof BearerTokenAuthenticationToken bearer)) {
				throw new InvalidBearerTokenException("Jeton de liaison attendu.");
			}
			try {
				return new LinkAuthentication(LinkPrincipal.of(tokens.verify(bearer.getToken())));
			}
			catch (InvalidLinkTokenException ex) {
				log.info("Jeton de liaison refusé : {}.", ex.reason());
				throw new InvalidBearerTokenException("Jeton de liaison refusé.");
			}
		};
	}

	private static void unauthorized(HttpServletRequest request, HttpServletResponse response, ApiErrorFactory errors,
			ApiErrorWriter writer) throws IOException {
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE, INVALID_TOKEN_CHALLENGE);
		writer.write(response, errors.create(ErrorCode.INVALID_TOKEN, null, request));
	}

	private static void denied(HttpServletRequest request, HttpServletResponse response, ApiErrorFactory errors,
			ApiErrorWriter writer) throws IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (!(authentication instanceof LinkAuthentication)) {
			// Refus par défaut d'une requête sans jeton valide : même réponse qu'un jeton absent.
			unauthorized(request, response, errors, writer);
			return;
		}
		if (request.getAttribute(INSUFFICIENT_SCOPE_ATTRIBUTE) != null) {
			response.setHeader(HttpHeaders.WWW_AUTHENTICATE, INSUFFICIENT_SCOPE_CHALLENGE);
			writer.write(response, errors.create(ErrorCode.INSUFFICIENT_SCOPE, null, request));
			return;
		}
		writer.write(response, errors.create(ErrorCode.ACCESS_DENIED, null, request));
	}

	/**
	 * Routes d'intégration servies : méthodes de contrôleur annotées {@link RequiresScope}. La portée exigée est lue une
	 * fois par requête, sur le gestionnaire que Spring MVC choisira.
	 */
	static final class ScopedEndpoints {

		private final ObjectProvider<RequestMappingHandlerMapping> handlerMapping;

		ScopedEndpoints(ObjectProvider<RequestMappingHandlerMapping> handlerMapping) {
			this.handlerMapping = handlerMapping;
		}

		boolean isScoped(HttpServletRequest request) {
			return !requiredScope(request).isEmpty();
		}

		AuthorizationDecision authorize(Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) {
			// Sans authentification, get() lève une exception : réponse 401 par le point d'entrée.
			if (!(authentication.get() instanceof LinkAuthentication link)) {
				return new AuthorizationDecision(false);
			}
			String scope = requiredScope(context.getRequest());
			if (!scope.isEmpty() && link.getPrincipal().hasScope(scope)) {
				return new AuthorizationDecision(true);
			}
			context.getRequest().setAttribute(INSUFFICIENT_SCOPE_ATTRIBUTE, scope);
			return new AuthorizationDecision(false);
		}

		/** Portée exigée par le gestionnaire de la requête ; chaîne vide s'il n'y en a pas. */
		private String requiredScope(HttpServletRequest request) {
			Object cached = request.getAttribute(REQUIRED_SCOPE_ATTRIBUTE);
			if (cached instanceof String scope) {
				return scope;
			}
			String scope = lookup(request);
			request.setAttribute(REQUIRED_SCOPE_ATTRIBUTE, scope);
			return scope;
		}

		private String lookup(HttpServletRequest request) {
			RequestMappingHandlerMapping mapping = this.handlerMapping.getIfAvailable();
			if (mapping == null) {
				return NO_SCOPE;
			}
			// La recherche du gestionnaire exige le chemin analysé, posé d'ordinaire par DispatcherServlet.
			Object previous = request.getAttribute(ServletRequestPathUtils.PATH_ATTRIBUTE);
			if (!(previous instanceof RequestPath)) {
				ServletRequestPathUtils.parseAndCache(request);
			}
			try {
				HandlerExecutionChain chain = mapping.getHandler(request);
				if (chain != null && chain.getHandler() instanceof HandlerMethod method) {
					RequiresScope required = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RequiresScope.class);
					return required != null ? required.value() : NO_SCOPE;
				}
				return NO_SCOPE;
			}
			catch (Exception ex) {
				// Méthode HTTP non servie, type de contenu refusé… : aucune portée, donc refus.
				return NO_SCOPE;
			}
			finally {
				if (!(previous instanceof RequestPath)) {
					ServletRequestPathUtils.clearParsedRequestPath(request);
				}
			}
		}

	}

	/** Liaisons désactivées : chaque requête de la chaîne reçoit la réponse d'une route inconnue. */
	static final class DisabledLinksFilter extends OncePerRequestFilter {

		private final boolean enabled;

		private final ApiErrorFactory errors;

		private final ApiErrorWriter writer;

		DisabledLinksFilter(boolean enabled, ApiErrorFactory errors, ApiErrorWriter writer) {
			this.enabled = enabled;
			this.errors = errors;
			this.writer = writer;
		}

		@Override
		protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
				throws ServletException, IOException {
			if (this.enabled) {
				chain.doFilter(request, response);
				return;
			}
			this.writer.write(response, this.errors.create(ErrorCode.RESOURCE_NOT_FOUND, null, request));
		}

	}

}
