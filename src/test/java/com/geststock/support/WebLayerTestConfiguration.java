package com.geststock.support;

import java.nio.charset.StandardCharsets;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import com.geststock.config.ClockConfig;
import com.geststock.config.CorsConfig;
import com.geststock.config.CorsProperties;
import com.geststock.exception.ApiErrorFactory;
import com.geststock.exception.ApiErrorWriter;
import com.geststock.security.JwtAuthenticationResolver;
import com.geststock.security.RestAccessDeniedHandler;
import com.geststock.security.RestAuthenticationEntryPoint;
import com.geststock.security.SecurityConfig;

/**
 * Socle technique à importer dans les tests {@code @WebMvcTest} : chaîne de sécurité, CORS
 * et fabrication des erreurs, exactement comme dans l'application. Le décodeur JWT et le
 * convertisseur sont des doublures (pas de base de données dans ces tests) ; l'utilisateur
 * est simulé par {@code @WithMockUser}. Les jetons réels sont testés dans {@code SecurityIT}.
 */
@TestConfiguration(proxyBeanMethods = false)
@EnableConfigurationProperties(CorsProperties.class)
@Import({ SecurityConfig.class, CorsConfig.class, ClockConfig.class, ApiErrorFactory.class, ApiErrorWriter.class,
		RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class })
public class WebLayerTestConfiguration {

	@Bean
	JwtDecoder testJwtDecoder() {
		byte[] key = "web-layer-test-key-0123456789-abcdefghij".getBytes(StandardCharsets.UTF_8);
		return NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256")).macAlgorithm(MacAlgorithm.HS256).build();
	}

	@Bean
	JwtAuthenticationResolver testJwtAuthenticationResolver() {
		return new JwtAuthenticationConverter()::convert;
	}

}
