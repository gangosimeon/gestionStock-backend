package com.geststock.common.health;

import java.time.Instant;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * État de santé de l'API.
 *
 * @param status      {@code UP} si l'API et ses dépendances répondent, {@code DOWN} sinon
 * @param application nom de l'application
 * @param version     version déployée
 * @param timestamp   horodatage UTC de la vérification
 * @param components  état de chaque dépendance (ex. {@code database})
 */
@Schema(name = "HealthResponse")
public record HealthResponse(
		@Schema(example = "UP") String status,
		@Schema(example = "geststock-backend") String application,
		@Schema(example = "0.0.1-SNAPSHOT") String version,
		Instant timestamp,
		@Schema(example = "{\"database\": \"UP\"}") Map<String, String> components) {

	public static final String UP = "UP";

	public static final String DOWN = "DOWN";

}
