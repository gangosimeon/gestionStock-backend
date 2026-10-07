package com.geststock.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Documentation OpenAPI ({@code /v3/api-docs}, interface {@code /swagger-ui.html}).
 * Désactivée en production via {@code springdoc.*.enabled=false}.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

	static final String BEARER_SCHEME = "bearerAuth";

	@Bean
	OpenAPI gestStockOpenApi(@Value("${geststock.api.version:dev}") String version) {
		return new OpenAPI()
			.info(new Info()
				.title("GestStock API")
				.version(version)
				.description("""
						API REST de GestStock (gestion de stock et gestion commerciale).

						Conventions : erreurs au format `ApiError` (champ `message` destiné à l'utilisateur), \
						listes au format `{items, total, ...}`, en-tête `X-Warehouse-Id` pour le dépôt courant, \
						authentification par jeton Bearer (JWT)."""))
			.components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
				.type(SecurityScheme.Type.HTTP)
				.scheme("bearer")
				.bearerFormat("JWT")))
			.addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
	}

}
