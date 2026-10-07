package com.geststock.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Paramètres CORS ({@code geststock.cors.*}).
 *
 * @param allowedOrigins   origines autorisées (ex. {@code http://localhost:4200}) ; vide = aucune
 * @param allowedMethods   méthodes HTTP autorisées
 * @param allowedHeaders   en-têtes acceptés en requête
 * @param exposedHeaders   en-têtes lisibles par le navigateur dans la réponse
 * @param allowCredentials {@code false} tant que l'authentification passe par l'en-tête Bearer
 * @param maxAge           durée de cache des requêtes preflight
 */
@ConfigurationProperties(prefix = "geststock.cors")
public record CorsProperties(
		@DefaultValue List<String> allowedOrigins,
		@DefaultValue({ "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS" }) List<String> allowedMethods,
		@DefaultValue({ "Authorization", "Content-Type", "X-Warehouse-Id", "Idempotency-Key", "X-Trace-Id" }) List<String> allowedHeaders,
		@DefaultValue({ "X-Trace-Id", "X-Result-Truncated", "X-Warehouse-Id", "Location", "Content-Disposition" }) List<String> exposedHeaders,
		@DefaultValue("false") boolean allowCredentials,
		@DefaultValue("1h") Duration maxAge) {
}
