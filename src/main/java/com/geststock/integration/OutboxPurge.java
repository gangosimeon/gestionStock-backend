package com.geststock.integration;

import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Purge du flux d'intégration de l'entreprise courante (lot 1, étape 9 ; D5, v11-D1 ; lot 3, G17, plan § 30.4),
 * livrée <b>désactivée</b> et en <b>simulation</b> ({@link IntegrationPurgeProperties}).
 *
 * <p>Borne {@code B} d'un passage, calculée <b>une seule fois</b>, dans cet ordre (v17-D11) :
 * <ol>
 * <li>plafond {@code P} = {@code last_seq}, lu dans la base de l'entreprise par une instruction distincte, validée
 * avant l'étape suivante ;</li>
 * <li>acquittements ({@link AckSource}) : liaisons actives ou suspendues de l'entreprise (base plateforme), puis leurs
 * acquittements ; une liaison sans acquittement bloque la purge (v17-D4) ;</li>
 * <li>{@code B = min(P, MAX(seq) - 1, premier seq de moins de la rétention - 1, plus petit acquittement)}.</li>
 * </ol>
 * Une liaison activée après la lecture de {@code P} fixe son {@code asOfSeq} à un {@code last_seq} au moins égal à
 * {@code P} ({@code last_seq} ne décroît jamais) : rien de ce qu'elle doit lire n'est supprimé (§ 30.5).
 *
 * <p>Éligibles : les événements séquencés tels que {@code repère < seq ≤ B}, <b>préfixe contigu</b> des {@code seq}
 * ({@code created_at} n'est pas monotone en {@code seq} ; invariant de réparation v11-D1 : {@code MIN(seq) - 1} =
 * repère). Jamais un événement sans {@code seq} (v17-D9) ; jamais l'événement de plus grand {@code seq}.
 *
 * <p>Suppression ({@code mode=DELETE}, explicite) : <b>un lot = une transaction</b> courte (v17-D10) : verrou de la
 * ligne {@code integration_feed_state} ({@code FOR UPDATE}, {@code lock_timeout} ; le séquenceur, en
 * {@code SKIP LOCKED}, passe son tour le temps d'un lot), suppression d'au plus {@code batch-size} événements, repère
 * avancé au plus grand {@code seq} supprimé dans la même transaction, jamais en recul. Aucun verrou entre deux lots.
 * Une erreur annule le lot en cours seul et arrête le passage ; le suivant reprend depuis le repère. Le passage
 * s'arrête au plus tard après {@code max-batches} lots ; la durée {@code max-duration} est vérifiée entre deux lots :
 * un lot commencé est mené à son terme, le premier lot a toujours lieu (v17-D16).
 *
 * <p>Simulation : même plafond, même borne, mêmes critères et mêmes limites de lots ; ne supprime rien, n'avance pas
 * le repère et ne prend aucun verrou (elle ne reproduit pas {@code lock_timeout}).
 *
 * <p>Maintenance de l'intégration (G20b, décision B) : un passage contrôlé ({@link PassControl}) vérifie, <b>avant
 * chaque lot</b>, que la maintenance tient toujours son bail (sinon aucun nouveau lot, pas même le premier :
 * {@link PassStop#LEASE_LOST}) et, à partir du deuxième lot, qu'il lui reste du budget (sinon arrêt propre :
 * {@link PassStop#LIMIT_REACHED}). Un lot commencé est mené à son terme et validé ; rien n'est vérifié pendant un lot.
 * Plafond, borne, préfixe contigu et repère sous verrou sont inchangés : la sécurité des données ne dépend pas du bail.
 */
@Component
class OutboxPurge {

	private static final Logger log = LoggerFactory.getLogger(OutboxPurge.class);

	private static final String READ_LAST_SEQ = "select last_seq from integration_feed_state where id = :id";

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
		if (properties.maxBatches() < 1) {
			throw new IllegalArgumentException("geststock.integration.purge.max-batches doit être positif");
		}
		if (properties.maxDuration() == null || properties.maxDuration().isNegative()) {
			throw new IllegalArgumentException("geststock.integration.purge.max-duration ne peut pas être négative");
		}
		this.jdbc = JdbcClient.create(dataSource);
		this.readOnly = new TransactionTemplate(transactionManager);
		this.readOnly.setReadOnly(true);
		this.readWrite = new TransactionTemplate(transactionManager);
		this.acknowledgements = acknowledgements;
		this.properties = properties;
	}

	/** Un passage, dans le mode configuré, sur la base de l'entreprise courante, sans autre limite que les siennes. */
	PurgeReport run() {
		return run(PassControl.UNLIMITED);
	}

	/** Un passage contrôlé par la maintenance (bail et budget vérifiés avant chaque lot, G20b). */
	PurgeReport run(PassControl control) {
		// 1. Plafond P, lu et validé AVANT la liste des liaisons (v17-D11). Hors transaction : instruction distincte.
		long ceiling = this.jdbc.sql(READ_LAST_SEQ).param("id", FeedSequencer.FEED_STATE_ID).query(Long.class).optional()
			.orElseThrow(IntegrationFeedStateMissingException::new);
		// 2. Liaisons actives ou suspendues, puis leurs acquittements.
		AckFloor floor = this.acknowledgements.floor();
		// 3. Borne du passage, jamais relevée ensuite.
		Bound bound = this.readOnly.execute(status -> bound(ceiling, floor));
		if (this.properties.mode() == PurgeMode.DELETE) {
			return delete(ceiling, floor, bound, control);
		}
		int batches = (int) Math.min(this.properties.maxBatches(),
				(bound.eligible() + this.properties.batchSize() - 1) / this.properties.batchSize());
		log.info("Purge du flux d'intégration (simulation) : {} événement(s) éligibles (seq {} à {}), {} lot(s) ; plafond {}, "
				+ "{} ; repère inchangé à {}", bound.eligible(), bound.watermark() + 1, bound.through(), batches, ceiling,
				describe(floor), bound.watermark());
		return new PurgeReport(PurgeMode.SIMULATION, ceiling, floor, bound.watermark(), bound.through(), bound.eligible(),
				batches, 0, bound.watermark(), PassStop.COMPLETE);
	}

	private PurgeReport delete(long ceiling, AckFloor floor, Bound bound, PassControl control) {
		long started = System.nanoTime();
		int batches = 0;
		long deleted = 0;
		long watermark = bound.watermark();
		PassStop stop = PassStop.COMPLETE;
		while (watermark < bound.through()) {
			// Limites du passage, à partir du deuxième lot seulement : le premier lot a toujours lieu (v17-D16).
			if (batches >= this.properties.maxBatches() || (batches > 0
					&& (System.nanoTime() - started >= this.properties.maxDuration().toNanos() || !control.timeLeft()))) {
				stop = PassStop.LIMIT_REACHED;
				break;
			}
			// Bail de la maintenance, avant chaque lot, le premier compris : perdu, aucun lot de plus.
			if (!control.leaseHeld()) {
				stop = PassStop.LEASE_LOST;
				break;
			}
			Batch batch = this.readWrite.execute(status -> batch(bound.through()));
			if (batch.deleted() == 0) {
				break;
			}
			batches++;
			deleted += batch.deleted();
			watermark = batch.watermark();
		}
		log.info("Purge du flux d'intégration : {} événement(s) supprimés en {} lot(s) ; repère {} → {} ; borne {}, plafond {}, {} ; {}",
				deleted, batches, bound.watermark(), watermark, bound.through(), ceiling, describe(floor), stop);
		return new PurgeReport(PurgeMode.DELETE, ceiling, floor, bound.watermark(), bound.through(), bound.eligible(),
				batches, deleted, watermark, stop);
	}

	/** Un lot, dans la transaction courante : verrou de la ligne d'état, suppression, repère. */
	private Batch batch(long through) {
		// Valeur numérique issue de la configuration : SET n'accepte pas de paramètre lié.
		this.jdbc.sql("set local lock_timeout = " + this.properties.lockTimeout().toMillis()).update();
		long watermark = watermark(LOCK_STATE);
		if (through <= watermark) {
			return new Batch(0, watermark);
		}
		List<Long> removed = this.jdbc.sql(DELETE_BATCH)
			.param("fromSeq", watermark)
			.param("throughSeq", through)
			.param("batchSize", this.properties.batchSize())
			.query(Long.class)
			.list();
		long largest = watermark;
		for (long seq : removed) {
			largest = Math.max(largest, seq);
		}
		if (largest > watermark) {
			this.jdbc.sql(ADVANCE_WATERMARK).param("seq", largest).param("id", FeedSequencer.FEED_STATE_ID).update();
		}
		return new Batch(removed.size(), largest);
	}

	/** Borne du passage : {@code min(P, MAX(seq) - 1, premier seq récent - 1, acquittement)} ; le repère si rien. */
	private Bound bound(long ceiling, AckFloor floor) {
		long watermark = watermark(READ_STATE);
		long through = watermark;
		Long maxSeq = nullableLong(this.jdbc.sql(MAX_SEQ));
		if (!(floor instanceof AckFloor.Blocked) && maxSeq != null) {
			through = Math.min(ceiling, maxSeq - 1);
			if (floor instanceof AckFloor.Acknowledged acknowledged) {
				through = Math.min(through, acknowledged.seq());
			}
			Long firstRecent = nullableLong(this.jdbc.sql(FIRST_RECENT_SEQ)
				.param("retentionSeconds", (double) this.properties.retention().toSeconds()));
			if (firstRecent != null) {
				through = Math.min(through, firstRecent - 1);
			}
			through = Math.max(through, watermark);
		}
		long eligible = through <= watermark ? 0
				: this.jdbc.sql(COUNT_ELIGIBLE).param("fromSeq", watermark).param("throughSeq", through).query(Long.class)
					.single();
		return new Bound(watermark, through, eligible);
	}

	private long watermark(String sql) {
		return this.jdbc.sql(sql).param("id", FeedSequencer.FEED_STATE_ID).query(Long.class).optional()
			.orElseThrow(IntegrationFeedStateMissingException::new);
	}

	/** Agrégat d'une ligne, {@code null} sur un ensemble vide ({@code max}, {@code min}). */
	private static Long nullableLong(JdbcClient.StatementSpec statement) {
		return statement.query((rs, row) -> rs.getObject(1, Long.class)).list().get(0);
	}

	private static String describe(AckFloor floor) {
		return switch (floor) {
			case AckFloor.NoLink noLink -> "aucune liaison";
			case AckFloor.Blocked blocked -> "bloquée (liaison sans acquittement)";
			case AckFloor.Acknowledged acknowledged -> "acquittement " + acknowledged.seq();
		};
	}

	/**
	 * @param watermark repère au calcul de la borne
	 * @param through   borne {@code B} ; le repère si rien n'est éligible
	 * @param eligible  événements tels que {@code repère < seq ≤ B}
	 */
	private record Bound(long watermark, long through, long eligible) {
	}

	/**
	 * @param deleted   événements supprimés par le lot
	 * @param watermark repère après le lot
	 */
	private record Batch(int deleted, long watermark) {
	}

}
