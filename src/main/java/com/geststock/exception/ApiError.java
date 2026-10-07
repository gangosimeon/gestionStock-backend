package com.geststock.exception;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Format unique des réponses d'erreur ({@code application/problem+json}), compatible
 * RFC 9457 et étendu pour le front GestStock.
 *
 * <p>Le front Angular affiche {@code message} : il doit toujours être renseigné, en français,
 * et ne jamais contenir de détail technique.
 */
@Schema(name = "ApiError", description = "Réponse d'erreur standard")
public record ApiError(
		@Schema(description = "Identifiant du type d'erreur (RFC 9457)", example = "urn:geststock:error:validation-failed")
		String type,
		@Schema(description = "Libellé HTTP du statut", example = "Bad Request")
		String title,
		@Schema(description = "Statut HTTP", example = "400")
		int status,
		@Schema(description = "Code d'erreur stable", example = "VALIDATION_FAILED")
		String code,
		@Schema(description = "Message destiné à l'utilisateur", example = "Données invalides : name ne doit pas être vide")
		String message,
		@Schema(description = "Identique à message (champ standard RFC 9457)")
		String detail,
		@Schema(description = "Erreurs de validation par champ (vide si non applicable)")
		List<FieldViolation> errors,
		@Schema(description = "Informations complémentaires propres au code d'erreur")
		Map<String, Object> details,
		@Schema(description = "Identifiant de corrélation, repris dans les logs et l'en-tête X-Trace-Id")
		String traceId,
		@Schema(description = "Horodatage UTC")
		Instant timestamp,
		@Schema(description = "Chemin de la requête", example = "/api/products")
		String path) {

	/** Erreur de validation sur un champ ou un paramètre. */
	@Schema(name = "FieldViolation")
	public record FieldViolation(
			@Schema(example = "items[0].quantity") String field,
			@Schema(example = "doit être supérieur à 0") String message) {
	}

}
