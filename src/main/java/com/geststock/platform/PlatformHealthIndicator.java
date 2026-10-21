package com.geststock.platform;

import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

/**
 * Santé actuator « db » ({@code /actuator/health/readiness}) : base plateforme. La source routée n'est
 * pas sondée (aucune entreprise courante hors requête).
 */
@Component("dbHealthIndicator")
class PlatformHealthIndicator extends AbstractHealthIndicator {

	private final PlatformDb platform;

	PlatformHealthIndicator(PlatformDb platform) {
		this.platform = platform;
	}

	@Override
	protected void doHealthCheck(Health.Builder builder) {
		if (this.platform.isReachable()) {
			builder.up();
		}
		else {
			builder.down();
		}
	}

}
