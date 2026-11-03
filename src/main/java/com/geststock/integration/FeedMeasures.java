package com.geststock.integration;

import java.util.Map;
import java.util.UUID;

/**
 * Mesures du flux de l'entreprise courante (lot 3, G20c ; G20-D7, plan § 33.5), calculées à la demande par la
 * maintenance à partir des données en base, sans jauge en mémoire. Les âges sont calculés par la base de l'entreprise
 * ({@code now()}), qui stocke les dates mesurées.
 *
 * <p>Les événements sans {@code seq} ne sont comptés qu'au niveau de l'entreprise ; les volumes de plusieurs liaisons
 * ne sont jamais additionnés.
 *
 * @param unsequenced              événements validés sans {@code seq}
 * @param oldestUnsequencedSeconds âge du plus ancien d'entre eux ; 0 s'il n'y en a aucun
 * @param lastSeq                  dernier {@code seq} attribué
 * @param purgedThroughSeq         repère de purge
 * @param links                    mesures des liaisons demandées qui ont un acquittement ; une liaison sans acquittement
 *                                 est absente
 */
public record FeedMeasures(long unsequenced, long oldestUnsequencedSeconds, long lastSeq, long purgedThroughSeq,
		Map<UUID, LinkFeedMeasures> links) {

	public FeedMeasures {
		links = Map.copyOf(links);
	}

	/**
	 * Mesures d'une liaison qui a acquitté.
	 *
	 * @param acknowledgedSeq          plus grand {@code seq} acquitté
	 * @param sinceAcknowledgedSeconds temps écoulé depuis le dernier acquittement accepté ({@code acknowledged_at})
	 * @param pending                  événements séquencés au-delà de l'acquittement
	 * @param oldestPendingSeconds     âge du plus ancien d'entre eux ({@code min(created_at)}) ; 0 s'il n'y en a aucun
	 * @param lag                      retard de séquence, {@code last_seq - acknowledged_seq} (indicatif : compte aussi
	 *                                 les événements que la liaison ne reçoit pas)
	 */
	public record LinkFeedMeasures(long acknowledgedSeq, long sinceAcknowledgedSeconds, long pending,
			long oldestPendingSeconds, long lag) {
	}

}
