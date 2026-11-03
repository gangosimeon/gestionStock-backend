package com.geststock.integration.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import com.geststock.integration.FeedMeasures;
import com.geststock.integration.FeedMeasures.LinkFeedMeasures;
import com.geststock.integration.link.LinkStatus;
import com.geststock.integration.link.RetainedLink;
import com.geststock.integration.maintenance.MaintenanceAlerts.Alert;
import com.geststock.integration.maintenance.MaintenanceAlerts.Severity;
import com.geststock.integration.maintenance.MaintenanceAlerts.Type;

/**
 * Règles d'alerte de la maintenance (lot 3, G20c ; G20-D15, plan § 33.5), sans base : au plus une alerte de
 * consommateur par liaison, la première règle qui s'applique ; seuils franchis strictement ; valeurs par défaut du plan.
 */
class MaintenanceAlertsTest {

	private static final long MINUTE = 60;

	private static final long HOUR = 60 * MINUTE;

	private static final long DAY = 24 * HOUR;

	private final MaintenanceAlerts alerts = new MaintenanceAlerts(defaults());

	// --- Seuils ---------------------------------------------------------------------------------------------------

	@Test
	void theDefaultThresholdsAreThoseOfThePlan() {
		IntegrationAlertProperties thresholds = defaults();
		assertThat(thresholds.noFirstAckWarning()).isEqualTo(Duration.ofHours(1));
		assertThat(thresholds.noFirstAckCritical()).isEqualTo(Duration.ofHours(24));
		assertThat(thresholds.noActivityWarning()).isEqualTo(Duration.ofHours(1));
		assertThat(thresholds.noActivityCritical()).isEqualTo(Duration.ofHours(6));
		assertThat(thresholds.consumerStalledWarning()).isEqualTo(Duration.ofHours(6));
		assertThat(thresholds.consumerStalledCritical()).isEqualTo(Duration.ofHours(24));
		assertThat(thresholds.suspensionWarning()).isEqualTo(Duration.ofDays(30));
		assertThat(thresholds.unsequencedWarning()).isEqualTo(Duration.ofMinutes(5));
		assertThat(thresholds.lockWaitWarning()).isEqualTo(Duration.ofSeconds(1));
	}

	@Test
	void aWarningThresholdMustBeBelowItsCriticalThresholdAndEveryThresholdPositive() {
		assertThatThrownBy(() -> bind(Map.of("no-first-ack-warning", "24h", "no-first-ack-critical", "24h")))
			.hasStackTraceContaining("no-first-ack");
		assertThatThrownBy(() -> bind(Map.of("no-activity-warning", "7h"))).hasStackTraceContaining("no-activity");
		assertThatThrownBy(() -> bind(Map.of("consumer-stalled-critical", "1h"))).hasStackTraceContaining("consumer-stalled");
		assertThatThrownBy(() -> bind(Map.of("lock-wait-warning", "0s"))).hasStackTraceContaining("lock-wait-warning");
		assertThatThrownBy(() -> bind(Map.of("suspension-warning", "-1d"))).hasStackTraceContaining("suspension-warning");
	}

	// --- Règle 1 : liaison suspendue ---------------------------------------------------------------------------

	@Test
	void aSuspendedLinkRaisesNoConsumerAlertAndOnlyWarnsAfterThirtyDays() {
		// Ni acquittement ni activité depuis longtemps : la suspension prime, aucune alerte de consommateur.
		assertThat(this.alerts.link(suspended(2 * DAY), null)).isEmpty();
		assertThat(this.alerts.link(suspended(2 * DAY), measures(10 * HOUR, 5, 30 * HOUR))).isEmpty();
		assertThat(this.alerts.link(suspended(30 * DAY), null)).as("seuil non franchi").isEmpty();
		assertThat(this.alerts.link(suspended(30 * DAY + 1), null))
			.contains(alert(Type.PURGE_BLOCKED_BY_SUSPENSION, Severity.WARNING));
	}

	// --- Règle 2 : aucun acquittement ----------------------------------------------------------------------------

	@Test
	void anActiveLinkThatNeverAcknowledgedWarnsAfterAnHourAndIsCriticalAfterADay() {
		assertThat(this.alerts.link(active(30 * MINUTE), null)).isEmpty();
		assertThat(this.alerts.link(active(HOUR), null)).isEmpty();
		assertThat(this.alerts.link(active(HOUR + 1), null)).contains(alert(Type.NO_FIRST_ACK, Severity.WARNING));
		assertThat(this.alerts.link(active(24 * HOUR), null)).contains(alert(Type.NO_FIRST_ACK, Severity.WARNING));
		assertThat(this.alerts.link(active(24 * HOUR + 1), null)).contains(alert(Type.NO_FIRST_ACK, Severity.CRITICAL));
	}

	// --- Règle 3 : rien en attente -------------------------------------------------------------------------------

	@Test
	void nothingPendingRaisesNoAlertWhateverTheAgeOfTheLastAcknowledgement() {
		assertThat(this.alerts.link(active(30 * DAY), measures(10 * DAY, 0, 0))).isEmpty();
	}

	// --- Règle 4 : aucune activité --------------------------------------------------------------------------------

	@Test
	void anOldAcknowledgementWithEventsPendingIsNoActivityBeforeAStalledConsumer() {
		assertThat(this.alerts.link(active(DAY), measures(HOUR, 3, 30 * HOUR))).as("seuil non franchi")
			.contains(alert(Type.CONSUMER_STALLED, Severity.CRITICAL));
		assertThat(this.alerts.link(active(DAY), measures(HOUR + 1, 3, 30 * HOUR)))
			.as("une seule alerte de consommateur : la première règle")
			.contains(alert(Type.NO_ACTIVITY, Severity.WARNING));
		assertThat(this.alerts.link(active(DAY), measures(6 * HOUR + 1, 3, 30 * HOUR)))
			.contains(alert(Type.NO_ACTIVITY, Severity.CRITICAL));
	}

	// --- Règle 5 : consommateur bloqué ----------------------------------------------------------------------------

	@Test
	void aRecentAcknowledgementWithAnOldPendingEventIsAStalledConsumer() {
		assertThat(this.alerts.link(active(DAY), measures(5 * MINUTE, 3, 6 * HOUR))).isEmpty();
		assertThat(this.alerts.link(active(DAY), measures(5 * MINUTE, 3, 6 * HOUR + 1)))
			.contains(alert(Type.CONSUMER_STALLED, Severity.WARNING));
		assertThat(this.alerts.link(active(DAY), measures(5 * MINUTE, 3, 24 * HOUR + 1)))
			.contains(alert(Type.CONSUMER_STALLED, Severity.CRITICAL));
	}

	// --- Entreprise ------------------------------------------------------------------------------------------------

	@Test
	void unsequencedEventsWarnOnlyWithAnActiveLinkAndBeyondFiveMinutes() {
		assertThat(this.alerts.unsequenced(true, company(4, 5 * MINUTE))).isEmpty();
		assertThat(this.alerts.unsequenced(true, company(4, 5 * MINUTE + 1)))
			.contains(alert(Type.UNSEQUENCED_EVENTS, Severity.WARNING));
		assertThat(this.alerts.unsequenced(false, company(4, DAY))).as("aucune liaison active").isEmpty();
		assertThat(this.alerts.unsequenced(true, company(0, 0))).isEmpty();
	}

	@Test
	void aLockWaitBeyondOneSecondWarns() {
		assertThat(this.alerts.lockWait(0)).isEmpty();
		assertThat(this.alerts.lockWait(1000)).isEmpty();
		assertThat(this.alerts.lockWait(1001)).contains(alert(Type.LOCK_WAIT, Severity.WARNING));
	}

	@Test
	void companyErrorsAndRepeatedInterruptionsAreCritical() {
		assertThat(MaintenanceAlerts.COMPANY_ERROR).isEqualTo(alert(Type.COMPANY_ERROR, Severity.CRITICAL));
		assertThat(MaintenanceAlerts.COMPANY_INTERRUPTED).isEqualTo(alert(Type.COMPANY_INTERRUPTED, Severity.CRITICAL));
	}

	// --- Outils --------------------------------------------------------------------------------------------------

	private static IntegrationAlertProperties defaults() {
		return bind(Map.of());
	}

	private static IntegrationAlertProperties bind(Map<String, String> values) {
		MapConfigurationPropertySource source = new MapConfigurationPropertySource();
		values.forEach((name, value) -> source.put("geststock.integration.maintenance.alerts." + name, value));
		return new Binder(source).bindOrCreate("geststock.integration.maintenance.alerts", IntegrationAlertProperties.class);
	}

	private static RetainedLink active(long sinceActivation) {
		return new RetainedLink(UUID.randomUUID(), LinkStatus.ACTIVE, sinceActivation, 0);
	}

	private static RetainedLink suspended(long sinceSuspension) {
		return new RetainedLink(UUID.randomUUID(), LinkStatus.SUSPENDED, 60 * DAY, sinceSuspension);
	}

	private static LinkFeedMeasures measures(long sinceAcknowledged, long pending, long oldestPending) {
		return new LinkFeedMeasures(10, sinceAcknowledged, pending, oldestPending, pending);
	}

	private static FeedMeasures company(long unsequenced, long oldestUnsequenced) {
		return new FeedMeasures(unsequenced, oldestUnsequenced, 10, 0, Map.of());
	}

	private static Alert alert(Type type, Severity severity) {
		return new Alert(type, severity);
	}

}
