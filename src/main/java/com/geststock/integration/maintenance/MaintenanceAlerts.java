package com.geststock.integration.maintenance;

import java.time.Duration;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.geststock.integration.FeedMeasures;
import com.geststock.integration.link.LinkStatus;
import com.geststock.integration.link.RetainedLink;

/**
 * Règles d'alerte de la maintenance (lot 3, G20c ; G20-D15, plan § 33.5), sans état : chaque règle reçoit des mesures
 * et rend au plus une alerte. Un seuil est franchi strictement au-delà de sa valeur ({@link IntegrationAlertProperties}).
 *
 * <p><b>Au plus une alerte de consommateur par liaison</b>, la première règle qui s'applique :
 * <ol>
 * <li>liaison suspendue : aucune alerte de consommateur ; {@code PURGE_BLOCKED_BY_SUSPENSION} au-delà du seuil de
 * suspension ;</li>
 * <li>aucun acquittement : {@code NO_FIRST_ACK} depuis l'activation ;</li>
 * <li>aucun événement séquencé en attente : aucune alerte ;</li>
 * <li>dernier acquittement ancien : {@code NO_ACTIVITY} ;</li>
 * <li>sinon, plus ancien événement en attente ancien : {@code CONSUMER_STALLED}.</li>
 * </ol>
 * Au niveau de l'entreprise, et en plus : {@code UNSEQUENCED_EVENTS} (seulement avec une liaison active),
 * {@code LOCK_WAIT}, {@code COMPANY_ERROR}, {@code COMPANY_INTERRUPTED}.
 */
@Component
class MaintenanceAlerts {

	/** Entreprise terminée en erreur pendant ce passage. */
	static final Alert COMPANY_ERROR = new Alert(Type.COMPANY_ERROR, Severity.CRITICAL);

	/** Entreprise interrompue au moins deux fois de suite au début du passage (G20-D13). */
	static final Alert COMPANY_INTERRUPTED = new Alert(Type.COMPANY_INTERRUPTED, Severity.CRITICAL);

	private final IntegrationAlertProperties thresholds;

	MaintenanceAlerts(IntegrationAlertProperties thresholds) {
		this.thresholds = thresholds;
	}

	/**
	 * Alerte de consommateur d'une liaison active ou suspendue.
	 * @param measures mesures de la liaison ; {@code null} si elle n'a jamais acquitté
	 */
	Optional<Alert> link(RetainedLink link, FeedMeasures.LinkFeedMeasures measures) {
		if (link.status() == LinkStatus.SUSPENDED) {
			return beyond(link.sinceSuspensionSeconds(), this.thresholds.suspensionWarning())
					? alert(Type.PURGE_BLOCKED_BY_SUSPENSION, Severity.WARNING) : Optional.empty();
		}
		if (measures == null) {
			return graded(Type.NO_FIRST_ACK, link.sinceActivationSeconds(), this.thresholds.noFirstAckWarning(),
					this.thresholds.noFirstAckCritical());
		}
		if (measures.pending() == 0) {
			return Optional.empty();
		}
		if (beyond(measures.sinceAcknowledgedSeconds(), this.thresholds.noActivityWarning())) {
			return graded(Type.NO_ACTIVITY, measures.sinceAcknowledgedSeconds(), this.thresholds.noActivityWarning(),
					this.thresholds.noActivityCritical());
		}
		return graded(Type.CONSUMER_STALLED, measures.oldestPendingSeconds(), this.thresholds.consumerStalledWarning(),
				this.thresholds.consumerStalledCritical());
	}

	/** Événements sans {@code seq} anciens, seulement si l'entreprise a au moins une liaison active. */
	Optional<Alert> unsequenced(boolean activeLink, FeedMeasures measures) {
		return activeLink && measures.unsequenced() > 0
				&& beyond(measures.oldestUnsequencedSeconds(), this.thresholds.unsequencedWarning())
						? alert(Type.UNSEQUENCED_EVENTS, Severity.WARNING) : Optional.empty();
	}

	/** Attente du verrou d'un lot de purge. */
	Optional<Alert> lockWait(long maxLockWaitMs) {
		return maxLockWaitMs > this.thresholds.lockWaitWarning().toMillis() ? alert(Type.LOCK_WAIT, Severity.WARNING)
				: Optional.empty();
	}

	private static Optional<Alert> graded(Type type, long seconds, Duration warning, Duration critical) {
		if (beyond(seconds, critical)) {
			return alert(type, Severity.CRITICAL);
		}
		return beyond(seconds, warning) ? alert(type, Severity.WARNING) : Optional.empty();
	}

	private static boolean beyond(long seconds, Duration threshold) {
		return seconds > threshold.toSeconds();
	}

	private static Optional<Alert> alert(Type type, Severity severity) {
		return Optional.of(new Alert(type, severity));
	}

	/** Types d'alerte, dans l'ordre du rapport (§ 33.4). */
	enum Type {

		NO_FIRST_ACK, NO_ACTIVITY, CONSUMER_STALLED, PURGE_BLOCKED_BY_SUSPENSION, UNSEQUENCED_EVENTS, COMPANY_ERROR,
		COMPANY_INTERRUPTED, LOCK_WAIT

	}

	/** Gravité : le workflow échoue sur une alerte {@code CRITICAL} (G20e). */
	enum Severity {

		WARNING, CRITICAL

	}

	record Alert(Type type, Severity severity) {
	}

}
