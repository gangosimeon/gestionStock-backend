package com.geststock.common.health;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.platform.PlatformDb;

/**
 * Point de contrôle public {@code GET /api/health}, destiné au front, aux sondes de
 * supervision et aux tests de démarrage. Ne révèle aucune information sensible : uniquement
 * l'état {@code UP}/{@code DOWN} de chaque dépendance.
 */
@RestController
@RequestMapping("/api/health")
@Tag(name = "Santé", description = "État de l'API")
class HealthController {

	private static final Logger log = LoggerFactory.getLogger(HealthController.class);

	private final PlatformDb platform;

	private final Clock clock;

	private final String applicationName;

	private final String version;

	HealthController(PlatformDb platform, Clock clock, ObjectProvider<BuildProperties> buildProperties,
			@Value("${spring.application.name}") String applicationName) {
		this.platform = platform;
		this.clock = clock;
		this.applicationName = applicationName;
		BuildProperties build = buildProperties.getIfAvailable();
		this.version = build != null ? build.getVersion() : "dev";
	}

	@GetMapping
	@SecurityRequirements
	@Operation(summary = "État de santé de l'API et de la base de données")
	@ApiResponse(responseCode = "200", description = "API opérationnelle")
	@ApiResponse(responseCode = "503", description = "Une dépendance est indisponible")
	ResponseEntity<HealthResponse> health() {
		String database = databaseStatus();
		String status = HealthResponse.UP.equals(database) ? HealthResponse.UP : HealthResponse.DOWN;
		HealthResponse body = new HealthResponse(status, this.applicationName, this.version, Instant.now(this.clock),
				Map.of("database", database));
		HttpStatus httpStatus = HealthResponse.UP.equals(status) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
		return ResponseEntity.status(httpStatus).body(body);
	}

	/** Base plateforme (annuaire des entreprises) : sans elle, aucune entreprise n'est joignable. */
	private String databaseStatus() {
		if (this.platform.isReachable()) {
			return HealthResponse.UP;
		}
		log.warn("Base plateforme injoignable.");
		return HealthResponse.DOWN;
	}

}
