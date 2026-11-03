package com.geststock.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import com.geststock.exception.ErrorCode;
import com.geststock.support.PostgresTestConfiguration;

/**
 * Purge du flux d'intégration (lot 1, étape 9 ; D5, v11-D1) sur un vrai PostgreSQL : livrée désactivée et en
 * simulation ; règles d'éligibilité (acquittement, rétention, jamais le plus grand {@code seq}, jamais un événement
 * sans {@code seq}, préfixe contigu) ; repère jamais en recul ; cohérence sous séquencement concurrent.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class OutboxPurgeIT {

	/** Liaison qui lit le flux (G4a : la disponibilité servie est celle de ses dépôts). */
	private static final LinkRef LINK = new LinkRef(UUID.randomUUID());

	private static final String FEED_STATE_ID = "00000000-0000-0000-0000-000000000001";

	@Autowired
	private FeedSequencer sequencer;

	@Autowired
	private ChangeFeedService feed;

	@Autowired
	private IntegrationPurgeProperties defaults;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private ApplicationContext context;

	private JdbcTemplate jdbc;

	@BeforeEach
	void setUp() {
		this.jdbc = new JdbcTemplate(this.dataSource);
		resetFeed();
	}

	@AfterEach
	void tearDown() {
		resetFeed();
	}

	// --- Livrée en simulation -------------------------------------------------------------------------------

	@Test
	void thePurgeIsDeliveredInSimulationWithNinetyDaysOfRetentionAndNoScheduledJob() {
		assertThat(this.defaults.mode()).isEqualTo(PurgeMode.SIMULATION);
		assertThat(this.defaults.retention()).isEqualTo(Duration.ofDays(90));
		assertThat(this.defaults.maxBatches()).isEqualTo(100);
		assertThat(this.defaults.maxDuration()).isEqualTo(Duration.ofSeconds(30));
		// G20-D10 : plus de tâche planifiée ; seule la maintenance de l'intégration déclenche la purge.
		assertThat(this.context.containsBean("outboxPurgeJob")).isFalse();
		assertThat(this.context.getBeansOfType(IntegrationScheduling.class)).isEmpty();
	}

	@Test
	void aBlockedFloorMakesNothingEligibleEvenInDeleteMode() {
		sequencedEvents(10, Duration.ofDays(100));
		PurgeReport report = purge(PurgeMode.DELETE, AckFloor::blocked).run();
		assertThat(report.eligibleCount()).isZero();
		assertThat(report.deletedCount()).isZero();
		assertThat(outboxCount()).isEqualTo(10);
		assertThat(purgedThrough()).isZero();
	}

	// --- Simulation ----------------------------------------------------------------------------------------

	@Test
	void aSimulationDeletesNothingAndReportsWhatWouldBeDeleted() {
		sequencedEvents(10, Duration.ofDays(100));
		PurgeReport report = purge(PurgeMode.SIMULATION, () -> AckFloor.acknowledged(8)).run();
		System.out.println("Simulation de purge : " + report);

		// Plafond 10, borne 8, lots de 3 : trois lots seraient faits.
		assertThat(report).isEqualTo(new PurgeReport(PurgeMode.SIMULATION, 10, AckFloor.acknowledged(8), 0, 8, 8, 3, 0, 0,
				PassStop.COMPLETE));
		assertThat(outboxCount()).isEqualTo(10);
		assertThat(purgedThrough()).isZero();
	}

	// --- Suppression réelle (test seulement, faux AckSource) ---------------------------------------------------

	@Test
	void onlyTheEligiblePrefixIsDeletedAndTheWatermarkIsTheLargestDeletedSeq() {
		sequencedEvents(10, Duration.ofDays(100));
		// seq 7 récent : la purge s'arrête avant lui, même si 8 à 10 sont anciens (préfixe contigu).
		this.jdbc.update("update integration_outbox set created_at = now() - interval '1 day' where seq = 7");
		unsequencedEvents(2, Duration.ofDays(200));

		PurgeReport report = purge(PurgeMode.DELETE, () -> AckFloor.acknowledged(9)).run();

		assertThat(report).isEqualTo(new PurgeReport(PurgeMode.DELETE, 10, AckFloor.acknowledged(9), 0, 6, 6, 2, 6, 6,
				PassStop.COMPLETE));
		assertThat(sequencedSeqs()).containsExactly(7L, 8L, 9L, 10L);
		assertThat(unsequencedCount()).as("événements sans seq conservés").isEqualTo(2);
		assertThat(purgedThrough()).isEqualTo(6L);

		long expired = 3;
		assertThatThrownBy(() -> this.feed.read(LINK, expired, null, ChangeFilter.ALL))
			.isInstanceOfSatisfying(CursorExpiredException.class,
					e -> assertThat(e.getCode()).isEqualTo(ErrorCode.CURSOR_EXPIRED));
		assertRepairInvariant();
	}

	@Test
	void theEventWithTheLargestSeqIsNeverPurgedAndTheWatermarkNeverGoesBack() {
		sequencedEvents(10, Duration.ofDays(100));

		PurgeReport all = purge(PurgeMode.DELETE, AckFloor::noLink).run();
		assertThat(all.deletedCount()).isEqualTo(9);
		assertThat(sequencedSeqs()).containsExactly(10L);
		assertThat(purgedThrough()).isEqualTo(9L);

		PurgeReport older = purge(PurgeMode.DELETE, () -> AckFloor.acknowledged(5)).run();
		assertThat(older.deletedCount()).isZero();
		assertThat(purgedThrough()).as("jamais de recul du repère").isEqualTo(9L);
		assertRepairInvariant();
	}

	@Test
	void aRecentEventIsKeptByTheRetention() {
		sequencedEvents(5, Duration.ofDays(10));
		PurgeReport report = purge(PurgeMode.DELETE, () -> AckFloor.acknowledged(5)).run();
		assertThat(report.deletedCount()).isZero();
		assertThat(outboxCount()).isEqualTo(5);
	}

	// --- Borne d'un passage (G17, plan § 30.4, v17-D11) -------------------------------------------------------

	@Test
	void theBoundNeverExceedsTheLastSeqReadAtTheStartOfThePass() {
		sequencedEvents(10, Duration.ofDays(100));
		// Les acquittements sont lus après le plafond : entre-temps, le flux avance jusqu'à 15 et l'acquittement aussi.
		AckSource growingFeed = () -> {
			unsequencedEvents(5, Duration.ofDays(100));
			this.sequencer.sequence();
			return AckFloor.acknowledged(15);
		};

		PurgeReport report = purge(PurgeMode.DELETE, growingFeed).run();

		assertThat(report.ceilingSeq()).as("P = last_seq lu avant les acquittements").isEqualTo(10L);
		assertThat(report.eligibleThroughSeq()).as("borne ≤ P").isEqualTo(10L);
		assertThat(sequencedSeqs()).as("rien au-delà de P").containsExactly(11L, 12L, 13L, 14L, 15L);
		assertThat(purgedThrough()).isEqualTo(10L);
		assertRepairInvariant();
	}

	@Test
	void aBlockedFloorKeepsEverythingAndNoLinkPurgesUpToTheLargestSeqMinusOne() {
		sequencedEvents(6, Duration.ofDays(100));
		unsequencedEvents(2, Duration.ofDays(200));

		PurgeReport blocked = purge(PurgeMode.DELETE, AckFloor::blocked).run();
		assertThat(blocked.floor()).isEqualTo(AckFloor.blocked());
		assertThat(blocked.deletedCount()).isZero();
		assertThat(outboxCount()).isEqualTo(8);

		PurgeReport noLink = purge(PurgeMode.DELETE, AckFloor::noLink).run();
		assertThat(noLink.eligibleThroughSeq()).isEqualTo(5L);
		assertThat(sequencedSeqs()).as("plus grand seq conservé").containsExactly(6L);
		assertThat(unsequencedCount()).as("événements sans seq conservés (v17-D9)").isEqualTo(2);
		assertRepairInvariant();
	}

	// --- Lots autonomes (G17, v17-D10, v17-D16) -----------------------------------------------------------------

	@Test
	void eachBatchIsItsOwnTransactionAndThePassStopsAtItsBatchLimit() {
		sequencedEvents(10, Duration.ofDays(100));

		PurgeReport report = purge(PurgeMode.DELETE, AckFloor::noLink, 2, Duration.ofSeconds(30)).run();

		assertThat(report.batches()).isEqualTo(2);
		assertThat(report.deletedCount()).as("deux lots de 3").isEqualTo(6);
		assertThat(report.eligibleCount()).as("borne inchangée : 9 éligibles").isEqualTo(9);
		assertThat(sequencedSeqs()).containsExactly(7L, 8L, 9L, 10L);
		assertThat(purgedThrough()).isEqualTo(6L);
		assertRepairInvariant();

		PurgeReport next = purge(PurgeMode.DELETE, AckFloor::noLink, 2, Duration.ofSeconds(30)).run();
		assertThat(next.deletedCount()).as("reprise depuis le repère").isEqualTo(3);
		assertThat(sequencedSeqs()).containsExactly(10L);
		assertRepairInvariant();
	}

	@Test
	void theDurationIsCheckedBetweenBatchesAndTheStartedBatchIsCompleted() {
		sequencedEvents(10, Duration.ofDays(100));

		PurgeReport report = purge(PurgeMode.DELETE, AckFloor::noLink, 100, Duration.ZERO).run();

		assertThat(report.batches()).as("un seul lot, mené à son terme").isEqualTo(1);
		assertThat(report.deletedCount()).isEqualTo(3);
		assertThat(purgedThrough()).isEqualTo(3L);
		assertRepairInvariant();
	}

	@Test
	void aFailingBatchIsRolledBackAloneAndTheNextPassResumesWithoutLossOrDuplicate() {
		sequencedEvents(10, Duration.ofDays(100));
		// Échec injecté dans le deuxième lot (seq 4 à 6) : un déclencheur de test refuse la suppression de seq 5.
		this.jdbc.execute("""
				create or replace function g17_refuse_seq_5() returns trigger language plpgsql as $$
				begin
				  if old.seq = 5 then raise exception 'échec injecté (test G17)'; end if;
				  return old;
				end $$""");
		this.jdbc.execute("""
				create trigger g17_refuse_seq_5 before delete on integration_outbox
				for each row execute function g17_refuse_seq_5()""");
		try {
			OutboxPurge purge = purge(PurgeMode.DELETE, AckFloor::noLink);
			assertThatThrownBy(purge::run).as("le passage s'arrête sur le lot en erreur").isInstanceOf(RuntimeException.class);
			assertThat(sequencedSeqs()).as("premier lot acquis, deuxième annulé en entier")
				.containsExactly(4L, 5L, 6L, 7L, 8L, 9L, 10L);
			assertThat(purgedThrough()).isEqualTo(3L);
			assertRepairInvariant();
		}
		finally {
			this.jdbc.execute("drop trigger if exists g17_refuse_seq_5 on integration_outbox");
			this.jdbc.execute("drop function if exists g17_refuse_seq_5()");
		}

		PurgeReport resumed = purge(PurgeMode.DELETE, AckFloor::noLink).run();
		assertThat(resumed.purgedThroughBefore()).isEqualTo(3L);
		assertThat(resumed.deletedCount()).isEqualTo(6);
		assertThat(sequencedSeqs()).containsExactly(10L);
		assertThat(purgedThrough()).isEqualTo(9L);
		assertRepairInvariant();
	}

	@Test
	void theSimulationComputesExactlyWhatTheDeletionDoesWithoutDeletingOrMovingTheMark() {
		sequencedEvents(10, Duration.ofDays(100));
		this.jdbc.update("update integration_outbox set created_at = now() - interval '1 day' where seq = 9");

		PurgeReport simulated = purge(PurgeMode.SIMULATION, () -> AckFloor.acknowledged(9), 2, Duration.ofSeconds(30)).run();
		assertThat(outboxCount()).isEqualTo(10);
		assertThat(purgedThrough()).isZero();

		PurgeReport deleted = purge(PurgeMode.DELETE, () -> AckFloor.acknowledged(9), 2, Duration.ofSeconds(30)).run();
		assertThat(simulated.ceilingSeq()).isEqualTo(deleted.ceilingSeq());
		assertThat(simulated.floor()).isEqualTo(deleted.floor());
		assertThat(simulated.eligibleThroughSeq()).isEqualTo(deleted.eligibleThroughSeq()).isEqualTo(8L);
		assertThat(simulated.eligibleCount()).isEqualTo(deleted.eligibleCount()).isEqualTo(8L);
		assertThat(simulated.batches()).as("mêmes limites de lots").isEqualTo(deleted.batches()).isEqualTo(2);
		assertThat(simulated.deletedCount()).isZero();
		assertThat(simulated.purgedThroughAfter()).isZero();
		assertThat(deleted.deletedCount()).isEqualTo(6);
	}

	// --- Concurrence avec le séquenceur ------------------------------------------------------------------------

	@Test
	void aPurgeConcurrentWithSequencingKeepsAConsistentState() throws Exception {
		OutboxPurge purge = purge(PurgeMode.DELETE, AckFloor::noLink);
		ExecutorService threads = Executors.newFixedThreadPool(2);
		try {
			for (int round = 0; round < 20; round++) {
				unsequencedEvents(3, Duration.ofDays(100));
				CyclicBarrier barrier = new CyclicBarrier(2);
				Future<PurgeReport> purged = threads.submit(() -> {
					barrier.await(10, TimeUnit.SECONDS);
					return purge.run();
				});
				Future<SequencingResult> sequenced = threads.submit(() -> {
					barrier.await(10, TimeUnit.SECONDS);
					return this.sequencer.sequence();
				});
				purged.get(20, TimeUnit.SECONDS);
				sequenced.get(20, TimeUnit.SECONDS);
				this.jdbc.update("update integration_outbox set created_at = now() - interval '100 days'");

				Map<String, Object> state = this.jdbc.queryForMap("select last_seq, purged_through_seq from integration_feed_state");
				Long maxSeq = this.jdbc.queryForObject("select max(seq) from integration_outbox", Long.class);
				if (maxSeq != null) {
					assertThat(state.get("last_seq")).as("tour %d", round).isEqualTo(maxSeq);
					assertRepairInvariant();
				}
			}
		}
		finally {
			threads.shutdownNow();
		}
		this.sequencer.sequence();
		assertThat(this.jdbc.queryForObject("select max(seq) from integration_outbox", Long.class))
			.isEqualTo(this.jdbc.queryForObject("select last_seq from integration_feed_state", Long.class));
		assertRepairInvariant();
	}

	// --- Outils ------------------------------------------------------------------------------------------------

	private OutboxPurge purge(PurgeMode mode, AckSource acks) {
		return purge(mode, acks, 100, Duration.ofSeconds(30));
	}

	/** Purge en lots de 3, avec ses limites de lots et de durée par passage. */
	private OutboxPurge purge(PurgeMode mode, AckSource acks, int maxBatches, Duration maxDuration) {
		return new OutboxPurge(this.dataSource, this.transactionManager, acks, new IntegrationPurgeProperties(mode,
				Duration.ofDays(90), 3, Duration.ofSeconds(2), maxBatches, maxDuration));
	}

	/** {@code count} événements séquencés (seq 1..count), écrits il y a {@code age}. */
	private void sequencedEvents(int count, Duration age) {
		unsequencedEvents(count, age);
		this.sequencer.sequence();
	}

	private void unsequencedEvents(int count, Duration age) {
		for (int i = 0; i < count; i++) {
			this.jdbc.update("""
					insert into integration_outbox (type, operation, object_id, object_version, created_at)
					values ('CATEGORY', 'UPSERT', ?, 1, now() - make_interval(secs => ?))""", UUID.randomUUID(),
					(double) age.toSeconds());
		}
	}

	/** Invariant de réparation (v11-D1) : MIN(seq) - 1 = repère de purge, tant qu'il reste un événement séquencé. */
	private void assertRepairInvariant() {
		Long minSeq = this.jdbc.queryForObject("select min(seq) from integration_outbox", Long.class);
		if (minSeq != null) {
			assertThat(minSeq - 1).as("MIN(seq) - 1 = repère").isEqualTo(purgedThrough());
		}
	}

	private List<Long> sequencedSeqs() {
		return this.jdbc.queryForList("select seq from integration_outbox where seq is not null order by seq", Long.class);
	}

	private long purgedThrough() {
		return this.jdbc.queryForObject("select purged_through_seq from integration_feed_state", Long.class);
	}

	private int outboxCount() {
		return this.jdbc.queryForObject("select count(*) from integration_outbox", Integer.class);
	}

	private int unsequencedCount() {
		return this.jdbc.queryForObject("select count(*) from integration_outbox where seq is null", Integer.class);
	}

	private void resetFeed() {
		this.jdbc.update("delete from integration_outbox");
		this.jdbc.update("""
				insert into integration_feed_state (id) values (?::uuid)
				on conflict (id) do update set last_seq = 0, purged_through_seq = 0""", FEED_STATE_ID);
	}

}
