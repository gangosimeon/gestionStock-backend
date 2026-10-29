package com.geststock.integration;

import java.util.List;
import java.util.OptionalLong;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Purge du flux d'intégration de l'entreprise courante (lot 1, étape 9 ; D5, v11-D1), livrée <b>désactivée</b> et en
 * <b>simulation</b> ({@link IntegrationPurgeProperties}).
 *
 * <p>Éligibles : les événements séquencés de {@code seq} supérieur au repère de purge et inférieur ou égal à la
 * borne {@code min(plus petit acquittement des liaisons actives, MAX(seq) - 1, premier seq trop récent - 1)}. La
 * borne forme un <b>préfixe contigu</b> des {@code seq} : {@code created_at} (début de la transaction d'écriture)
 * n'est pas monotone en {@code seq}, et supprimer un événement ancien au-delà d'un récent ferait mentir le repère
 * (invariant de réparation v11-D1 : {@code MIN(seq) - 1} = repère). Jamais un événement sans {@code seq} ; jamais
 * l'événement de plus grand {@code seq} ; sans acquittement (lot 1 : aucune liaison), rien n'est éligible.
 *
 * <p>Simulation : calcule et journalise ce qui serait supprimé, sans rien supprimer ni verrouiller. Suppression
 * ({@code mode=DELETE}, explicite) : dans une transaction, verrou de la ligne {@code integration_feed_state}
 * ({@code FOR UPDATE}, {@code lock_timeout}, sans {@code SKIP LOCKED} : le séquenceur, lui, passe son tour),
 * suppression par lots, puis repère = plus grand {@code seq} supprimé, jamais en recul.
 */
@Component
class OutboxPurge {

	private static final Logger log = LoggerFactory.getLogger(OutboxPurge.class);

	private static final String READ_STATE = "select purged_through_seq from integration_feed_state where id = :id";

	private static final String LOCK_STATE = READ_STATE + " for update";

	private static final String MAX_SEQ = "select max(seq) from integration_outbox where seq is not null";

	private static final String FIRST_RECENT_SEQ = """
			select min(seq) from integration_outbox
			where seq is not null and created_at >= now() - make_interval(secs => :retentionSeconds)""";

	private static final String COUNT_ELIGIBLE = """
			select count(*) from integration_outbox where seq > :fromSeq and seq <= :throughSeq""";

	private static final String DELETE_BATCH = """
			delete from integration_outbox
			where id in (
			    select id from integration_outbox
			    where seq > :fromSeq and seq <= :throughSeq
			    order by seq
			    limit :batchSize)
			returning seq""";

	private static final String ADVANCE_WATERMARK = """
			update integration_feed_state set purged_through_seq = :seq, updated_at = now()
			where id = :id and purged_through_seq < :seq""";

	private final JdbcClient jdbc;

	private final TransactionTemplate readOnly;

	private final TransactionTemplate readWrite;

	private final AckSource acknowledgements;

	private final IntegrationPurgeProperties properties;

	OutboxPurge(DataSource dataSource, PlatformTransactionManager transactionManager, AckSource acknowledgements,
			IntegrationPurgeProperties properties) {
		if (properties.batchSize() < 1) {
			throw new IllegalArgumentException("geststock.integration.purge.batch-size doit être positif");
		}
		this.jdbc = JdbcClient.create(dataSource);
		this.readOnly = new TransactionTemplate(transactionManager);
		this.readOnly.setReadOnly(true);
		this.readWrite = new TransactionTemplate(transactionManager);
		this.acknowledgements = acknowledgements;
		this.properties = properties;
	}

	/** Un passage, dans le mode configuré, sur la base de l'entreprise courante. */
	PurgeReport run() {
		OptionalLong acknowledged = this.acknowledgements.minimumAcknowledged();
		if (this.properties.mode() == PurgeMode.DELETE) {
			return this.readWrite.execute(status -> delete(acknowledged));
		}
		return this.readOnly.execute(status -> simulate(acknowledged));
	}

	private PurgeReport simulate(OptionalLong acknowledged) {
		long watermark = watermark(READ_STATE);
		long through = eligibleThrough(acknowledged, watermark);
		long eligible = eligibleCount(watermark, through);
		log.info("Purge du flux d'intégration (simulation) : {} événement(s) seraient supprimés (seq {} à {}) ; "
				+ "repère inchangé à {}", eligible, watermark + 1, through, watermark);
		return new PurgeReport(PurgeMode.SIMULATION, watermark, through, eligible, 0, watermark);
	}

	private PurgeReport delete(OptionalLong acknowledged) {
		// Valeur numérique issue de la configuration : SET n'accepte pas de paramètre lié.
		this.jdbc.sql("set local lock_timeout = " + this.properties.lockTimeout().toMillis()).update();
		long watermark = watermark(LOCK_STATE);
		long through = eligibleThrough(acknowledged, watermark);
		long eligible = eligibleCount(watermark, through);
		long deleted = 0;
		long largestDeleted = watermark;
		List<Long> batch;
		do {
			batch = this.jdbc.sql(DELETE_BATCH)
				.param("fromSeq", watermark)
				.param("throughSeq", through)
				.param("batchSize", this.properties.batchSize())
				.query(Long.class)
				.list();
			deleted += batch.size();
			for (long seq : batch) {
				largestDeleted = Math.max(largestDeleted, seq);
			}
		}
		while (!batch.isEmpty());
		if (largestDeleted > watermark) {
			this.jdbc.sql(ADVANCE_WATERMARK).param("seq", largestDeleted).param("id", FeedSequencer.FEED_STATE_ID).update();
		}
		log.info("Purge du flux d'intégration : {} événement(s) supprimés ; repère {} → {}", deleted, watermark,
				largestDeleted);
		return new PurgeReport(PurgeMode.DELETE, watermark, through, eligible, deleted, largestDeleted);
	}

	private long watermark(String sql) {
		return this.jdbc.sql(sql).param("id", FeedSequencer.FEED_STATE_ID).query(Long.class).optional()
			.orElseThrow(IntegrationFeedStateMissingException::new);
	}

	/** Plus grand {@code seq} éligible ; le repère lui-même si rien ne l'est. */
	private long eligibleThrough(OptionalLong acknowledged, long watermark) {
		if (acknowledged.isEmpty()) {
			return watermark;
		}
		Long maxSeq = nullableLong(this.jdbc.sql(MAX_SEQ));
		if (maxSeq == null) {
			return watermark;
		}
		long through = Math.min(acknowledged.getAsLong(), maxSeq - 1);
		Long firstRecent = nullableLong(this.jdbc.sql(FIRST_RECENT_SEQ)
			.param("retentionSeconds", (double) this.properties.retention().toSeconds()));
		if (firstRecent != null) {
			through = Math.min(through, firstRecent - 1);
		}
		return Math.max(through, watermark);
	}

	/** Agrégat d'une ligne, {@code null} sur un ensemble vide ({@code max}, {@code min}). */
	private static Long nullableLong(JdbcClient.StatementSpec statement) {
		return statement.query((rs, row) -> rs.getObject(1, Long.class)).list().get(0);
	}

	private long eligibleCount(long watermark, long through) {
		if (through <= watermark) {
			return 0;
		}
		return this.jdbc.sql(COUNT_ELIGIBLE).param("fromSeq", watermark).param("throughSeq", through).query(Long.class)
			.single();
	}

}
