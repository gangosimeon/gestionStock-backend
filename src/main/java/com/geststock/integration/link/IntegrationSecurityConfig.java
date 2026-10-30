package com.geststock.integration.link;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
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
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
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
import com.geststock.tenancy.TenantContext;
import com.geststock.tenancy.TenantSchemaRegistry;

/**
 * Chaîne de sécurité de l'intégration boutique (lot 2, étapes G6 et G7 ; v13-D6, v14-D4, v14-D10, C2).
 *
 * <ul>
 * <li>Limitée à {@code /api/integration/v1/**}, placée avant la chaîne principale ({@code SecurityConfig}, inchangée) :
 * aucune requête d'intégration n'atteint la chaîne principale.</li>
 * <li>Sans état, sans session, sans CSRF (aucun cookie), sans CORS (aucune origine navigateur : aucun en-tête
 * {@code Access-Control-*}), {@code Cache-Control: no-store} sur toutes les réponses.</li>
 * <li>Authentification : {@code Authorization: Bearer} vérifié par {@link IntegrationTokenService} (aucun bean
 * {@code JwtDecoder}), puis la liaison relue en base à chaque appel ({@link LinkAccess}, sans cache) : liaison
 * inconnue, d'un autre client ou en attente ⇒ {@code 401 INVALID_TOKEN} ; révoquée ⇒ {@code 401 LINK_REVOKED}.
 * Portées effectives = portées du jeton ∩ portées de la liaison en base.</li>
 * <li>Autorisation, dans l'ordre : liaison suspendue hors des routes autorisées pendant une suspension ⇒
 * {@code 403 LINK_SUSPENDED} ; entreprise non active ⇒ {@code 403 COMPANY_SUSPENDED} ou {@code COMPANY_UNAVAILABLE} ;
 * base de l'entreprise en migration ⇒ {@code 503 SCHEMA_UPGRADE_IN_PROGRESS} ou {@code SCHEMA_UPGRADE_FAILED} ;
 * portée absente ⇒ {@code 403 INSUFFICIENT_SCOPE}. Seules les méthodes de contrôleur portant {@link RequiresScope}
 * sont servies ; les routes du client ({@code oauth/token}, {@code oauth/par}, étape G8) exigent l'authentification
 * HTTP Basic du client ({@link ClientAuthenticationFilter}, {@code 401 invalid_client} sinon) et ignorent tout jeton
 * Bearer ; tout le reste est refusé. Elles sont limitées en débit ({@link RateLimitFilter}, étape G9 :
 * {@code 429 RATE_LIMITED}, {@code 503 RATE_LIMIT_UNAVAILABLE}) : par IP avant cette authentification, par client
 * après une authentification réussie seulement.</li>
 * <li>Entreprise courante : établie après l'autorisation, uniquement depuis {@code company_id} de la liaison (jamais
 * depuis la requête), et vidée en fin de requête, exception comprise ({@code TenantContextFilter} la vide aussi avant et
 * après toute la chaîne). Exception : {@code POST /link/revoke} ({@code platformOnly}, C2) ne touche que la base
 * plateforme ; il n'a ni contrôle d'entreprise ni contrôle de migration, et aucune entreprise courante.</li>
 * <li>Liaisons désactivées ({@code geststock.integration.links.enabled=false}) : 404 sur toute la chaîne, comme une
 * route inconnue.</li>
 * </ul>
 * La raison d'un refus n'est jamais détaillée dans la réponse au-delà de son code : elle est journalisée avec
 * l'identifiant de la liaison, jamais avec le jeton.
 */
@Configuration(proxyBeanMethods = false)
public class IntegrationSecurityConfig {

	private static final Logger log = LoggerFactory.getLogger(IntegrationSecurityConfig.class);

	public static final String BASE_PATH = "/api/integration/v1";

	/** Routes authentifiées par le client JeCréeMaBoutique (HTTP Basic, étape G8), jamais par un jeton de liaison. */
	public static final String[] CLIENT_AUTHENTICATED_PATHS = { BASE_PATH + "/oauth/token", BASE_PATH + "/oauth/par" };

	/**
	 * Seules routes qui peuvent porter {@code allowedWhileSuspended} et {@link RequiresScope#ANY} (v14-D10 : statut et
	 * révocation de la liaison restent possibles pendant une suspension). Vérifié par {@code EndpointSecurityCoverageIT}.
	 */
	public static final Set<String> SUSPENSION_ALLOWED_ENDPOINTS = Set.of("GET " + BASE_PATH + "/link",
			"POST " + BASE_PATH + "/link/revoke");

	/**
	 * Seules routes qui peuvent porter {@code platformOnly} (C2 : la boutique peut toujours se déconnecter, même si
	 * l'entreprise est suspendue, indisponible ou en migration ; la révocation ne touche que la base plateforme).
	 * Vérifié par {@code EndpointSecurityCoverageIT}.
	 */
	public static final Set<String> PLATFORM_ONLY_ENDPOINTS = Set.of("POST " + BASE_PATH + "/link/revoke");

	static final String INVALID_TOKEN_CHALLENGE = "Bearer error=\"invalid_token\"";

	static final String INSUFFICIENT_SCOPE_CHALLENGE = "Bearer error=\"insufficient_scope\"";

	private static final String ROUTE_RULE_ATTRIBUTE = IntegrationSecurityConfig.class.getName() + ".ROUTE_RULE";

	private static final String DENIAL_ATTRIBUTE = IntegrationSecurityConfig.class.getName() + ".DENIAL";

	@Bean
	@Order(Ordered.HIGHEST_PRECEDENCE)
	SecurityFilterChain integrationSecurityFilterChain(HttpSecurity http, IntegrationLinkProperties links,
			IntegrationTokenService tokens, LinkAccess access, IntegrationClients clients, TenantSchemaRegistry schemas,
			IntegrationRateLimitProperties rateLimits, RateLimiter limiter, LinkAudit audit, ApiErrorFactory errors,
			ApiErrorWriter writer,
			@Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> handlerMapping)
			throws Exception {
		ScopedEndpoints endpoints = new ScopedEndpoints(handlerMapping, schemas);
		AuthenticationEntryPoint entryPoint = (request, response, ex) -> unauthorized(request, response,
				ex instanceof LinkRevokedException ? ErrorCode.LINK_REVOKED : ErrorCode.INVALID_TOKEN, errors, writer);
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
				.authenticationManagerResolver(request -> linkTokenAuthentication(tokens, access))
				.authenticationEntryPoint(entryPoint)
				.accessDeniedHandler(accessDenied))
			.authorizeHttpRequests(authorize -> authorize
				// Routes du client (étape G8) : client authentifié en HTTP Basic par ClientAuthenticationFilter.
				.requestMatchers(CLIENT_AUTHENTICATED_PATHS)
				.access((authentication, context) -> new AuthorizationDecision(authentication.get() instanceof ClientAuthentication))
				.requestMatchers(endpoints::isScoped).access(endpoints::authorize)
				.anyRequest().denyAll())
			.addFilterAfter(new DisabledLinksFilter(links.enabled(), errors, writer), HeaderWriterFilter.class)
			.addFilterBefore(new ClientAuthenticationFilter(clientPaths(), clients, audit, rateLimits.clientIpHeader()),
					BearerTokenAuthenticationFilter.class)
			// Limitation de débit (G9) : par IP avant l'authentification du client (essais de secrets compris), par client
			// après une authentification réussie seulement (l'identifiant annoncé n'épuise jamais le quota du client).
			.addFilterBefore(new RateLimitFilter.ByIp(rateLimits, limiter, errors, writer), ClientAuthenticationFilter.class)
			.addFilterBefore(new RateLimitFilter.ByClient(rateLimits, limiter, errors, writer), AuthorizationFilter.class)
			.addFilterAfter(new LinkCompanyFilter(), AuthorizationFilter.class);
		return http.build();
	}

	/** Jeton lu dans l'en-tête {@code Authorization} seulement, et ignoré sur les routes du client. */
	static BearerTokenResolver bearerTokenResolver() {
		DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
		delegate.setAllowUriQueryParameter(false);
		delegate.setAllowFormEncodedBodyParameter(false);
		RequestMatcher clientPaths = clientPaths();
		return request -> clientPaths.matches(request) ? null : delegate.resolve(request);
	}

	/** Routes du client ({@code oauth/token}, {@code oauth/par}), toutes méthodes HTTP. */
	static RequestMatcher clientPaths() {
		return new OrRequestMatcher(Arrays.stream(CLIENT_AUTHENTICATED_PATHS)
			.map(path -> (RequestMatcher) PathPatternRequestMatcher.pathPattern(path))
			.toList());
	}

	/**
	 * Jeton vérifié, puis liaison relue en base (une requête, sans cache). Inconnue, d'un autre client ou en attente :
	 * jeton invalide ; révoquée : {@code LINK_REVOKED}. Suspendue : authentifiée, la suite dépend de la route.
	 */
	private static AuthenticationManager linkTokenAuthentication(IntegrationTokenService tokens, LinkAccess access) {
		return authentication -> {
			if (!(authentication instanceof BearerTokenAuthenticationToken bearer)) {
				throw new InvalidBearerTokenException("Jeton de liaison attendu.");
			}
			LinkTokenClaims claims;
			try {
				claims = tokens.verify(bearer.getToken());
			}
			catch (InvalidLinkTokenException ex) {
				log.info("Jeton de liaison refusé : {}.", ex.reason());
				throw new InvalidBearerTokenException("Jeton de liaison refusé.");
			}
			LinkAccess.LinkState state = access.load(claims.linkId()).orElse(null);
			if (state == null || !state.clientId().equals(claims.clientId())) {
				log.info("Liaison {} refusée : {}.", claims.linkId(), state == null ? "inconnue" : "client différent");
				throw new InvalidBearerTokenException("Jeton de liaison refusé.");
			}
			if (state.status() == LinkStatus.PENDING) {
				log.info("Liaison {} refusée : en attente.", claims.linkId());
				throw new InvalidBearerTokenException("Jeton de liaison refusé.");
			}
			if (state.status() == LinkStatus.REVOKED) {
				log.info("Liaison {} refusée : révoquée.", claims.linkId());
				throw new LinkRevokedException();
			}
			Set<String> effective = new HashSet<>(claims.scopes());
			effective.retainAll(state.scopes());
			return new LinkAuthentication(new LinkPrincipal(claims.linkId(), claims.clientId(), effective), state.status(),
					state.company(), state.companyStatus());
		};
	}

	private static void unauthorized(HttpServletRequest request, HttpServletResponse response, ErrorCode code,
			ApiErrorFactory errors, ApiErrorWriter writer) throws IOException {
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE, INVALID_TOKEN_CHALLENGE);
		writer.write(response, errors.create(code, null, request));
	}

	private static void denied(HttpServletRequest request, HttpServletResponse response, ApiErrorFactory errors,
			ApiErrorWriter writer) throws IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (!(authentication instanceof LinkAuthentication)) {
			// Refus par défaut d'une requête sans jeton valide : même réponse qu'un jeton absent.
			unauthorized(request, response, ErrorCode.INVALID_TOKEN, errors, writer);
			return;
		}
		ErrorCode code = request.getAttribute(DENIAL_ATTRIBUTE) instanceof ErrorCode denial ? denial : ErrorCode.ACCESS_DENIED;
		if (code == ErrorCode.INSUFFICIENT_SCOPE) {
			response.setHeader(HttpHeaders.WWW_AUTHENTICATE, INSUFFICIENT_SCOPE_CHALLENGE);
		}
		writer.write(response, errors.create(code, null, request));
	}

	/**
	 * Règle d'une route d'intégration servie : portée exigée, accès pendant une suspension, route limitée à la base
	 * plateforme (sans entreprise courante).
	 */
	private record RouteRule(String scope, boolean allowedWhileSuspended, boolean platformOnly) {

		static final RouteRule NONE = new RouteRule("", false, false);

	}

	/**
	 * Routes d'intégration servies : méthodes de contrôleur annotées {@link RequiresScope}. La règle est lue une fois par
	 * requête, sur le gestionnaire que Spring MVC choisira, puis appliquée avec l'état de la liaison et de l'entreprise.
	 */
	static final class ScopedEndpoints {

		private final ObjectProvider<RequestMappingHandlerMapping> handlerMapping;

		private final TenantSchemaRegistry schemas;

		ScopedEndpoints(ObjectProvider<RequestMappingHandlerMapping> handlerMapping, TenantSchemaRegistry schemas) {
			this.handlerMapping = handlerMapping;
			this.schemas = schemas;
		}

		boolean isScoped(HttpServletRequest request) {
			return rule(request) != RouteRule.NONE;
		}

		AuthorizationDecision authorize(Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) {
			// Sans authentification, get() lève une exception : réponse 401 par le point d'entrée.
			if (!(authentication.get() instanceof LinkAuthentication link)) {
				return new AuthorizationDecision(false);
			}
			HttpServletRequest request = context.getRequest();
			RouteRule rule = rule(request);
			ErrorCode denial = denial(link, rule);
			if (denial != null) {
				log.info("Liaison {} refusée : {} ({} {}).", link.getName(), denial, request.getMethod(), request.getRequestURI());
				request.setAttribute(DENIAL_ATTRIBUTE, denial);
				return new AuthorizationDecision(false);
			}
			log.debug("Liaison {} autorisée ({} {}).", link.getName(), request.getMethod(), request.getRequestURI());
			return new AuthorizationDecision(true);
		}

		/**
		 * Refus éventuel, dans l'ordre : suspension, entreprise, migration, portée. Une route limitée à la base plateforme
		 * ({@code platformOnly}) n'a ni contrôle d'entreprise ni contrôle de migration.
		 */
		private ErrorCode denial(LinkAuthentication link, RouteRule rule) {
			if (link.linkStatus() == LinkStatus.SUSPENDED && !rule.allowedWhileSuspended()) {
				return ErrorCode.LINK_SUSPENDED;
			}
			if (!rule.platformOnly()) {
				if (!"ACTIVE".equals(link.companyStatus())) {
					return "SUSPENDED".equals(link.companyStatus()) ? ErrorCode.COMPANY_SUSPENDED : ErrorCode.COMPANY_UNAVAILABLE;
				}
				var migration = this.schemas.blockingState(link.company().companyId());
				if (migration.isPresent()) {
					return migration.get() == TenantSchemaRegistry.State.UPGRADING ? ErrorCode.SCHEMA_UPGRADE_IN_PROGRESS
							: ErrorCode.SCHEMA_UPGRADE_FAILED;
				}
			}
			Set<String> scopes = link.getPrincipal().scopes();
			boolean granted = RequiresScope.ANY.equals(rule.scope()) ? !scopes.isEmpty() : link.getPrincipal().hasScope(rule.scope());
			return granted ? null : ErrorCode.INSUFFICIENT_SCOPE;
		}

		private RouteRule rule(HttpServletRequest request) {
			if (request.getAttribute(ROUTE_RULE_ATTRIBUTE) instanceof RouteRule cached) {
				return cached;
			}
			RouteRule rule = lookup(request);
			request.setAttribute(ROUTE_RULE_ATTRIBUTE, rule);
			return rule;
		}

		private RouteRule lookup(HttpServletRequest request) {
			RequestMappingHandlerMapping mapping = this.handlerMapping.getIfAvailable();
			if (mapping == null) {
				return RouteRule.NONE;
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
					return required != null && !required.value().isBlank()
							? new RouteRule(required.value(), required.allowedWhileSuspended(), required.platformOnly())
							: RouteRule.NONE;
				}
				return RouteRule.NONE;
			}
			catch (Exception ex) {
				// Méthode HTTP non servie, type de contenu refusé… : aucune règle, donc refus.
				return RouteRule.NONE;
			}
			finally {
				if (!(previous instanceof RequestPath)) {
					ServletRequestPathUtils.clearParsedRequestPath(request);
				}
			}
		}

	}

	/**
	 * Entreprise courante d'un appel autorisé : celle de la liaison, posée après l'autorisation et vidée à la fin de la
	 * requête, même en cas d'exception. Aucune pour une route limitée à la base plateforme ({@code platformOnly}).
	 */
	static final class LinkCompanyFilter extends OncePerRequestFilter {

		@Override
		protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
				throws ServletException, IOException {
			boolean platformOnly = request.getAttribute(ROUTE_RULE_ATTRIBUTE) instanceof RouteRule rule && rule.platformOnly();
			if (platformOnly || !(SecurityContextHolder.getContext().getAuthentication() instanceof LinkAuthentication link)) {
				chain.doFilter(request, response);
				return;
			}
			TenantContext.set(link.company());
			try {
				chain.doFilter(request, response);
			}
			finally {
				TenantContext.clear();
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
