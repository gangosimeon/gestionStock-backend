package com.geststock.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Documentation OpenAPI ({@code /v3/api-docs}, interface {@code /swagger-ui.html}).
 * Désactivée en production via {@code springdoc.*.enabled=false}. Les compléments déduits du
 * code (permissions, erreurs, exemples) sont dans {@link OpenApiCustomization}.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

	static final String BEARER_SCHEME = "bearerAuth";

	/** Client JeCréeMaBoutique (HTTP Basic) : seules les routes {@code /api/integration/v1/oauth/**} l'exigent. */
	public static final String INTEGRATION_CLIENT_SCHEME = "integrationClient";

	/** Erreur au format OAuth (RFC 6749 § 5.2) des routes du client, distincte d'{@code ApiError}. */
	public static final String OAUTH_ERROR_SCHEMA = "OAuthError";

	static final String GUIDE = """
			API REST de GestStock (gestion de stock et gestion commerciale d'un magasin d'optique).

			## Authentification

			1. `POST /api/auth/login` `{username, password}` → `{accessToken, refreshToken, expiresIn, user}`.
			2. Chaque appel : en-tête `Authorization: Bearer <accessToken>` (jeton valable `expiresIn` secondes).
			3. Sur `401 INVALID_TOKEN` (jeton expiré ou invalide) : `POST /api/auth/refresh` `{refreshToken}` renvoie une
			   nouvelle paire ; l'ancien refresh token est révoqué (rotation). Si le refresh échoue, se reconnecter.
			4. `POST /api/auth/logout` `{refreshToken}` ferme la session.

			Rôles et permissions sont relus en base à chaque requête (jamais pris dans le jeton) :
			`GET /api/auth/me` renvoie l'utilisateur et ses permissions, à utiliser pour afficher les menus.
			Chaque opération indique la permission exigée (description et extension `x-permissions`) ;
			sans elle : `403 ACCESS_DENIED`.

			## Conventions

			| Sujet | Règle |
			|---|---|
			| Dépôt courant | En-tête `X-Warehouse-Id` (UUID) sur les routes concernées ; absent : dépôt par défaut de l'utilisateur, sinon dépôt principal ; un dépôt invalide, inconnu, désactivé ou hors périmètre est refusé (400 ou 403), jamais remplacé. `allWarehouses=true` (permission `warehouse:all`) pour tous les dépôts. |
			| Listes | `{items, total, page, size, totalPages}` ; `page` (à partir de 0), `size` (1 à 200), `sort=champ,asc\\|desc` (champs listés par route). Sans `page` ni `size`, certaines listes historiques du front renvoient tout (5 000 au plus, en-tête `X-Result-Truncated` au-delà). Les rapports sont toujours paginés et portent leurs totaux dans `summary`. |
			| Dates | Instants ISO-8601 UTC (`2026-10-10T09:30:00Z`) ; dates seules `AAAA-MM-JJ`, journées dans le fuseau de l'entreprise (Abidjan). |
			| Montants | Décimaux en FCFA, 2 décimales ; prix, coûts et totaux toujours calculés par le serveur (les prix envoyés sont ignorés). |
			| Identifiants | UUID. |
			| Traçabilité | En-tête `X-Trace-Id` (envoyé ou généré) renvoyé dans chaque réponse et dans les erreurs (`traceId`). |

			## Erreurs

			Toutes les erreurs ont la forme `ApiError` (RFC 9457) : `code` stable à tester côté front, `message`
			en français à afficher, `errors` (champs invalides) et `details` (contexte : quantités disponibles,
			plafond…). Statuts : `400` requête invalide, `401` non authentifié, `403` permission ou dépôt, `404`
			introuvable, `409` conflit d'état ou modification concurrente, `422` règle métier, `500` erreur interne.

			""";

	@Bean
	OpenAPI gestStockOpenApi(@Value("${geststock.api.version:dev}") String version) {
		return new OpenAPI()
			.info(new Info()
				.title("GestStock API")
				.version(version)
				.description(GUIDE + "### Catalogue des codes d'erreur\n\n" + OpenApiCustomization.errorCatalog()))
			.addServersItem(new Server().url("/").description("Serveur courant"))
			.components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
				.type(SecurityScheme.Type.HTTP)
				.scheme("bearer")
				.bearerFormat("JWT")
				.description("Jeton d'accès obtenu par POST /api/auth/login ou /api/auth/refresh."))
				.addSecuritySchemes(INTEGRATION_CLIENT_SCHEME, new SecurityScheme()
					.type(SecurityScheme.Type.HTTP)
					.scheme("basic")
					.description("Identifiant et secret du client d'intégration (RFC 6749 § 2.3.1)."))
				.addSchemas(OAUTH_ERROR_SCHEMA, new ObjectSchema()
					.addProperty("error", new StringSchema()._enum(java.util.List.of("invalid_request", "invalid_client",
							"invalid_grant", "invalid_scope", "unsupported_grant_type")))
					.addProperty("error_description", new StringSchema())
					.addProperty("code", new StringSchema()._enum(java.util.List.of("LINK_REVOKED"))
						.description("Présent seulement quand la liaison est révoquée (400 invalid_grant)."))
					.required(java.util.List.of("error", "error_description"))))
			.addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
	}

}
