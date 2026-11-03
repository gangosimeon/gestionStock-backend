package com.geststock.integration.maintenance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import com.geststock.exception.ApiErrorFactory;
import com.geststock.exception.ApiErrorWriter;
import com.geststock.exception.ErrorCode;

/**
 * Chaîne de sécurité des routes internes {@code /internal/**} (lot 3, G20a ; G20-D1, D2, D14 ; plan § 33.3).
 *
 * <ul>
 * <li>Placée avant la chaîne principale et après celle de l'intégration : aucune requête {@code /internal/**} n'atteint
 * le décodeur des jetons utilisateurs ni celui des jetons de liaison. Sans état, sans session, sans CSRF, sans CORS,
 * {@code Cache-Control: no-store}.</li>
 * <li>Maintenance désactivée : {@code 404} sur toute la chaîne, comme une route inconnue.</li>
 * <li>Secret du cron ({@code Authorization: Bearer}) : son empreinte SHA-256 est comparée en temps constant à chaque
 * empreinte configurée (deux pendant une rotation), <b>sans accès à la base</b>. Absent ou faux : {@code 401
 * INVALID_TOKEN} ; l'échec n'est compté qu'en mémoire ({@link MaintenanceAuthFailures}). Un jeton utilisateur ou de
 * liaison n'est qu'un secret faux.</li>
 * <li>Seule {@code POST /internal/integration/maintenance} est servie ; tout le reste : {@code 404}.</li>
 * </ul>
 * Ni le secret, ni son empreinte, ni l'en-tête {@code Authorization} ne sont jamais journalisés.
 */
@Configuration(proxyBeanMethods = false)
public class MaintenanceSecurityConfig {

	public static final String ROUTE = "/internal/integration/maintenance";

	@Bean
	@Order(Ordered.HIGHEST_PRECEDENCE + 1)
	SecurityFilterChain maintenanceSecurityFilterChain(HttpSecurity http, IntegrationMaintenanceProperties properties,
			MaintenanceAuthFailures failures, ApiErrorFactory errors, ApiErrorWriter writer) throws Exception {
		http
			.securityMatcher("/internal/**")
			.csrf(AbstractHttpConfigurer::disable)
			.cors(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.requestCache(AbstractHttpConfigurer::disable)
			.anonymous(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.exceptionHandling(exceptions -> exceptions
				.authenticationEntryPoint((request, response, ex) -> unauthorized(request, response, errors, writer))
				.accessDeniedHandler((request, response, ex) -> writer.write(response,
						errors.create(ErrorCode.RESOURCE_NOT_FOUND, null, request))))
			.authorizeHttpRequests(authorize -> authorize
				.requestMatchers(HttpMethod.POST, ROUTE).authenticated()
				.anyRequest().denyAll())
			.addFilterAfter(new CronSecretFilter(properties, failures, errors, writer), HeaderWriterFilter.class);
		return http.build();
	}

	private static void unauthorized(HttpServletRequest request, HttpServletResponse response, ApiErrorFactory errors,
			ApiErrorWriter writer) throws IOException {
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
		writer.write(response, errors.create(ErrorCode.INVALID_TOKEN, null, request));
	}

	/** Maintenance désactivée ⇒ 404 ; secret du cron vérifié sans base ⇒ authentifié, sinon 401. */
	static final class CronSecretFilter extends OncePerRequestFilter {

		private static final String BEARER = "Bearer ";

		private final boolean enabled;

		private final List<byte[]> fingerprints;

		private final MaintenanceAuthFailures failures;

		private final ApiErrorFactory errors;

		private final ApiErrorWriter writer;

		CronSecretFilter(IntegrationMaintenanceProperties properties, MaintenanceAuthFailures failures, ApiErrorFactory errors,
				ApiErrorWriter writer) {
			this.enabled = properties.enabled();
			this.fingerprints = properties.fingerprints();
			this.failures = failures;
			this.errors = errors;
			this.writer = writer;
		}

		@Override
		protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
				throws ServletException, IOException {
			if (!this.enabled) {
				this.writer.write(response, this.errors.create(ErrorCode.RESOURCE_NOT_FOUND, null, request));
				return;
			}
			if (!authenticated(request.getHeader(HttpHeaders.AUTHORIZATION))) {
				this.failures.record();
				unauthorized(request, response, this.errors, this.writer);
				return;
			}
			SecurityContextHolder.setContext(new SecurityContextImpl(
					UsernamePasswordAuthenticationToken.authenticated("maintenance-cron", null, List.of())));
			chain.doFilter(request, response);
		}

		/** Toutes les empreintes sont comparées, en temps constant, sans sortie anticipée. */
		private boolean authenticated(String authorization) {
			if (authorization == null || authorization.length() <= BEARER.length()
					|| !authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
				return false;
			}
			byte[] presented = sha256(authorization.substring(BEARER.length()));
			boolean matches = false;
			for (byte[] fingerprint : this.fingerprints) {
				matches |= MessageDigest.isEqual(presented, fingerprint);
			}
			return matches;
		}

		private static byte[] sha256(String value) {
			try {
				return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			}
			catch (NoSuchAlgorithmException ex) {
				throw new IllegalStateException(ex);
			}
		}

	}

}
