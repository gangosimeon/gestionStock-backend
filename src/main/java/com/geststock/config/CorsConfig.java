package com.geststock.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Configuration CORS appliquée par Spring Security ({@code http.cors(...)}), donc avant
 * toute vérification d'authentification : les requêtes preflight du front Angular
 * aboutissent même sans jeton.
 */
@Configuration(proxyBeanMethods = false)
public class CorsConfig {

	@Bean
	CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
		CorsConfiguration cors = new CorsConfiguration();
		cors.setAllowedOrigins(properties.allowedOrigins());
		cors.setAllowedMethods(properties.allowedMethods());
		cors.setAllowedHeaders(properties.allowedHeaders());
		cors.setExposedHeaders(properties.exposedHeaders());
		cors.setAllowCredentials(properties.allowCredentials());
		cors.setMaxAge(properties.maxAge());

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", cors);
		return source;
	}

}
