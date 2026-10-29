package com.geststock.integration;

import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Séquenceur du flux d'intégration (conception D, plan d'intégration § 20.2) : attribue un {@code seq}
 * aux événements <b>validés</b> qui n'en ont pas, dans l'ordre de {@code write_id}.
 *
 * <p>Garanties :
 * <ul>
 * <li>un événement d'une transaction non validée est invisible (READ COMMITTED) : il ne reçoit pas de
 * {@code seq} ; validé plus tard, il reçoit un {@code seq} supérieur à tous ceux déjà attribués, donc un
 * consommateur ne le saute jamais ;</li>
 * <li>un seul séquenceur à la fois, par le verrou de la ligne unique {@code integration_feed_state}
 * ({@code FOR UPDATE SKIP LOCKED}) : un second appel concurrent est <b>ignoré</b> sans attendre ;</li>
 * <li>attribution et {@code last_seq} dans la même transaction courte : visibles ensemble au commit, ou pas
 * du tout ; une interruption n'attribue rien et l'appel suivant reprend tout ;</li>
 * <li>jamais appelé dans une transaction active (sémantique {@code NEVER}) : une seule connexion à la fois
 * (pool de quelques connexions par entreprise), et l'attribution est validée avant toute lecture.</li>
 * </ul>
 *
 * <p><b>Réparation si la ligne d'état a disparu</b> ({@link IntegrationFeedStateMissingException}) : le
 * séquencement est arrêté, aucun {@code seq} n'est attribué, les événements restent en attente. Recréer la
 * ligne, avec le compte propriétaire de la base de l'entreprise (décision v11-D1) : {@code last_seq = MAX(seq)}
 * et {@code purged_through_seq = MIN(seq) - 1} sur les événements séquencés, (0, 0) s'il n'y en a aucun :
 *
 * <pre>
 * INSERT INTO integration_feed_state (id, last_seq, purged_through_seq)
 * SELECT '00000000-0000-0000-0000-000000000001', COALESCE(MAX(seq), 0), COALESCE(MIN(seq) - 1, 0)
 * FROM integration_outbox
 * WHERE seq IS NOT NULL;
 * </pre>
 *
 * Les {@code seq} sont contigus et la purge supprime les plus petits : le plus petit {@code seq} restant moins 1
 * est exactement le repère de purge. <b>Invariant de purge</b> : l'événement de plus grand {@code seq} n'est
 * jamais purgé ; {@code MAX(seq)} reste donc connu et l'attribution reprend sans réutiliser un {@code seq}
 * (contrainte unique {@code uk_integration_outbox_seq}).
 */
@Component
class FeedSequencer {

	/** Clé constante de la ligne unique d'état du flux (migration V047). */
	static final UUID FEED_STATE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private static final String LOCK_STATE = """
			select last_seq from integration_feed_state where id = :id for update skip locked""";

	private static final String READ_STATE = """
			select last_seq from integration_feed_state where id = :id""";

	/** Les événements validés en attente, dans l'ordre d'écriture, au plus un lot ; seq contigus après last_seq. */
	private static final String ASSIGN_SEQS = """
			update integration_outbox o
			set seq = batch.seq
			from (
			    select pending.id, :lastSeq + row_number() over (order by pending.write_id) as seq
			    from (
			        select id, write_id from integration_outbox
			        where seq is null
			        order by write_id
			        limit :batchSize
			    ) pending
			) batch
			where o.id = batch.id""";

	private static final String ADVANCE_STATE = """
			update integration_feed_state set last_seq = :lastSeq, updated_at = now() where id = :id""";

	private final JdbcClient jdbc;

	private final TransactionTemplate transaction;

	private final FeedSequencerProperties properties;

	/** Point d'injection des tests (échec entre les deux UPDATE) ; sans effet en production. */
	private final Runnable afterOutboxUpdate;

	@Autowired
	FeedSequencer(DataSource dataSource, PlatformTransactionManager transactionManager, FeedSequencerProperties properties) {
		this(dataSource, transactionManager, properties, () -> {
		});
	}

	FeedSequencer(DataSource dataSource, PlatformTransactionManager transactionManager, FeedSequencerProperties properties,
			Runnable afterOutboxUpdate) {
		if (properties.batchSize() < 1) {
			throw new IllegalArgumentException("geststock.integration.sequencer.batch-size doit être positif");
		}
		this.jdbc = JdbcClient.create(dataSource);
		this.transaction = new TransactionTemplate(transactionManager);
		this.properties = properties;
		this.afterOutboxUpdate = afterOutboxUpdate;
	}

	/**
	 * Attribue un {@code seq} au prochain lot d'événements validés, dans une transaction courte et dédiée.
	 *
	 * @return le résultat de l'appel ; {@link SequencingResult#skipped()} si un autre séquenceur travaillait
	 * @throws IllegalTransactionStateException     appel depuis une transaction active
	 * @throws IntegrationFeedStateMissingException ligne d'état absente (réparation : voir la classe)
	 */
	SequencingResult sequence() {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalTransactionStateException(
					"Le séquenceur du flux ouvre sa propre transaction : il ne doit pas être appelé dans une transaction active");
		}
		return this.transaction.execute(status -> assign());
	}

	private SequencingResult assign() {
		// Valeurs numériques issues de la configuration : SET n'accepte pas de paramètre lié.
		this.jdbc.sql("set local lock_timeout = " + this.properties.lockTimeout().toMillis()).update();
		this.jdbc.sql("set local statement_timeout = " + this.properties.statementTimeout().toMillis()).update();

		Optional<Long> locked = this.jdbc.sql(LOCK_STATE).param("id", FEED_STATE_ID).query(Long.class).optional();
		if (locked.isEmpty()) {
			// Zéro ligne : verrouillée par un autre séquenceur, ou absente. Lecture sans verrou pour trancher.
			long committed = this.jdbc.sql(READ_STATE)
				.param("id", FEED_STATE_ID)
				.query(Long.class)
				.optional()
				.orElseThrow(IntegrationFeedStateMissingException::new);
			return SequencingResult.skipped(committed);
		}
		long lastSeq = locked.get();
		int batchSize = this.properties.batchSize();
		int assigned = this.jdbc.sql(ASSIGN_SEQS).param("lastSeq", lastSeq).param("batchSize", batchSize).update();
		this.afterOutboxUpdate.run();
		if (assigned == 0) {
			return new SequencingResult(0, lastSeq, false, false);
		}
		long newLastSeq = lastSeq + assigned;
		this.jdbc.sql(ADVANCE_STATE).param("lastSeq", newLastSeq).param("id", FEED_STATE_ID).update();
		return new SequencingResult(assigned, newLastSeq, false, assigned == batchSize);
	}

}
