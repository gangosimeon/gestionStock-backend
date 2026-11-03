package com.geststock.integration.maintenance;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Seuils des alertes de la maintenance ({@code geststock.integration.maintenance.alerts.*}, lot 3, G20c ; G20-D15,
 * plan § 33.5). Une alerte se déclenche strictement au-delà de son seuil ; les valeurs par défaut tolèrent un passage
 * du cron manqué.
 *
 * @param noFirstAckWarning       liaison active sans aucun acquittement, depuis son activation : {@code WARNING}
 * @param noFirstAckCritical      idem : {@code CRITICAL}
 * @param noActivityWarning       dernier acquittement accepté, avec des événements en attente : {@code WARNING}
 * @param noActivityCritical      idem : {@code CRITICAL}
 * @param consumerStalledWarning  plus ancien événement en attente : {@code WARNING}
 * @param consumerStalledCritical idem : {@code CRITICAL}
 * @param suspensionWarning       durée de suspension d'une liaison, qui bloque la purge : {@code WARNING}
 * @param unsequencedWarning      plus ancien événement sans {@code seq} d'une entreprise qui a une liaison active :
 *                                {@code WARNING}
 * @param lockWaitWarning         attente du verrou d'un lot de purge : {@code WARNING}
 */
@ConfigurationProperties(prefix = "geststock.integration.maintenance.alerts")
public record IntegrationAlertProperties(@DefaultValue("1h") Duration noFirstAckWarning,
		@DefaultValue("24h") Duration noFirstAckCritical, @DefaultValue("1h") Duration noActivityWarning,
		@DefaultValue("6h") Duration noActivityCritical, @DefaultValue("6h") Duration consumerStalledWarning,
		@DefaultValue("24h") Duration consumerStalledCritical, @DefaultValue("30d") Duration suspensionWarning,
		@DefaultValue("5m") Duration unsequencedWarning, @DefaultValue("1s") Duration lockWaitWarning) {

	public IntegrationAlertProperties {
		positive(noFirstAckWarning, "no-first-ack-warning");
		positive(noFirstAckCritical, "no-first-ack-critical");
		positive(noActivityWarning, "no-activity-warning");
		positive(noActivityCritical, "no-activity-critical");
		positive(consumerStalledWarning, "consumer-stalled-warning");
		positive(consumerStalledCritical, "consumer-stalled-critical");
		positive(suspensionWarning, "suspension-warning");
		positive(unsequencedWarning, "unsequenced-warning");
		positive(lockWaitWarning, "lock-wait-warning");
		ordered(noFirstAckWarning, noFirstAckCritical, "no-first-ack");
		ordered(noActivityWarning, noActivityCritical, "no-activity");
		ordered(consumerStalledWarning, consumerStalledCritical, "consumer-stalled");
	}

	private static void positive(Duration value, String property) {
		if (value == null || value.isNegative() || value.isZero()) {
			throw new IllegalArgumentException(
					"geststock.integration.maintenance.alerts." + property + " : durée positive attendue.");
		}
	}

	private static void ordered(Duration warning, Duration critical, String alert) {
		if (warning.compareTo(critical) >= 0) {
			throw new IllegalArgumentException("geststock.integration.maintenance.alerts." + alert
					+ "-warning doit être inférieur à " + alert + "-critical.");
		}
	}

}
