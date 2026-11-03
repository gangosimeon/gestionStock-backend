package com.geststock.integration;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Mesures du flux de l'entreprise courante (lot 3, G20c ; G20-D7, plan § 33.5), lues à la demande dans sa base :
 * remplace les jauges en mémoire de {@code FeedMetrics} (lot 1, Q5), retirées avec leur tâche planifiée (G20-D10).
 *
 * <ul>
 * <li>Entreprise : événements sans {@code seq} et âge du plus ancien (index partiel {@code seq IS NULL}),
 * {@code last_seq}, repère de purge.</li>
 * <li>Liaison qui a acquitté : événements séquencés au-delà de son acquittement et âge du plus ancien, lus par l'index
 * unique sur {@code seq} à partir de l'acquittement ; temps depuis le dernier acquittement accepté.</li>
 * </ul>
 * Tous les âges sont calculés par cette base ({@code now()}), qui stocke les dates mesurées.
 */
@Component
class FeedHealth {

	static final String UNSEQUENCED = """
			select count(*) as unsequenced,
			       coalesce(floor(extract(epoch from now() - min(created_at))), 0)::bigint as oldest_age_seconds
			from integration_outbox
			where seq is null""";

	private static final String STATE = """
			select last_seq, purged_through_seq from integration_feed_state where id = :id""";

	static final String LINKS = """
			select a.link_id, a.acknowledged_seq,
			       floor(extract(epoch from now() - a.acknowledged_at))::bigint as since_acknowledged_seconds,
			       p.pending, coalesce(floor(extract(epoch from now() - p.oldest)), 0)::bigint as oldest_pending_seconds
			from integration_link_ack a
			cross join lateral (
			    select count(*) as pending, min(o.created_at) as oldest
			    from integration_outbox o
			    where o.seq > a.acknowledged_seq) p
			where a.link_id in (:links)""";

	private final JdbcClient jdbc;

	FeedHealth(DataSource dataSource) {
		this.jdbc = JdbcClient.create(dataSource);
	}

	/** Mesures de l'entreprise courante et de ces liaisons ; une liaison sans acquittement est absente. */
	FeedMeasures measure(Set<UUID> links) {
		long[] unsequenced = this.jdbc.sql(UNSEQUENCED)
			.query((rs, row) -> new long[] { rs.getLong("unsequenced"), rs.getLong("oldest_age_seconds") })
			.single();
		long[] state = this.jdbc.sql(STATE).param("id", FeedSequencer.FEED_STATE_ID)
			.query((rs, row) -> new long[] { rs.getLong("last_seq"), rs.getLong("purged_through_seq") })
			.optional().orElseThrow(IntegrationFeedStateMissingException::new);
		Map<UUID, FeedMeasures.LinkFeedMeasures> measured = new HashMap<>();
		if (!links.isEmpty()) {
			this.jdbc.sql(LINKS).param("links", links)
				.query((rs, row) -> measured.put(rs.getObject("link_id", UUID.class), new FeedMeasures.LinkFeedMeasures(
						rs.getLong("acknowledged_seq"), rs.getLong("since_acknowledged_seconds"), rs.getLong("pending"),
						rs.getLong("oldest_pending_seconds"), Math.max(0, state[0] - rs.getLong("acknowledged_seq")))))
				.list();
		}
		return new FeedMeasures(unsequenced[0], unsequenced[1], state[0], state[1], measured);
	}

}
