package com.geststock.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
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
	private AckSource acknowledgements;

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

	// --- Livrée désactivée ---------------------------------------------------------------------------------

	@Test
	void thePurgeIsDeliveredDisabledInSimulationWithNinetyDaysOfRetention() {
		assertThat(this.defaults.enabled()).isFalse();
		assertThat(this.defaults.mode()).isEqualTo(PurgeMode.SIMULATION);
		assertThat(this.defaults.retention()).isEqualTo(Duration.ofDays(90));
		assertThat(this.context.getBeansOfType(OutboxPurgeJob.class)).isEmpty();
	}

	@Test
	void withoutAcknowledgementNothingIsEligibleEvenInDeleteMode() {
		sequencedEvents(10, Duration.ofDays(100));
		PurgeReport report = purge(PurgeMode.DELETE, this.acknowledgements).run();
		assertThat(report.eligibleCount()).isZero();
		assertThat(report.deletedCount()).isZero();
		assertThat(outboxCount()).isEqualTo(10);
		assertThat(purgedThrough()).isZero();
	}

	// --- Simulation ----------------------------------------------------------------------------------------

	@Test
	void aSimulationDeletesNothingAndReportsWhatWouldBeDeleted() {
		sequencedEvents(10, Duration.ofDays(100));
		PurgeReport report = purge(PurgeMode.SIMULATION, () -> OptionalLong.of(8)).run();
		System.out.println("Simulation de purge : " + report);

		assertThat(report).isEqualTo(new PurgeReport(PurgeMode.SIMULATION, 0, 8, 8, 0, 0));
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

		PurgeReport report = purge(PurgeMode.DELETE, () -> OptionalLong.of(9)).run();

		assertThat(report).isEqualTo(new PurgeReport(PurgeMode.DELETE, 0, 6, 6, 6, 6));
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

		PurgeReport all = purge(PurgeMode.DELETE, () -> OptionalLong.of(1_000)).run();
		assertThat(all.deletedCount()).isEqualTo(9);
		assertThat(sequencedSeqs()).containsExactly(10L);
		assertThat(purgedThrough()).isEqualTo(9L);

		PurgeReport older = purge(PurgeMode.DELETE, () -> OptionalLong.of(5)).run();
		assertThat(older.deletedCount()).isZero();
		assertThat(purgedThrough()).as("jamais de recul du repère").isEqualTo(9L);
		assertRepairInvariant();
	}

	@Test
	void aRecentEventIsKeptByTheRetention() {
		sequencedEvents(5, Duration.ofDays(10));
		PurgeReport report = purge(PurgeMode.DELETE, () -> OptionalLong.of(5)).run();
		assertThat(report.deletedCount()).isZero();
		assertThat(outboxCount()).isEqualTo(5);
	}

	// --- Concurrence avec le séquenceur ------------------------------------------------------------------------

	@Test
	void aPurgeConcurrentWithSequencingKeepsAConsistentState() throws Exception {
		OutboxPurge purge = purge(PurgeMode.DELETE, () -> OptionalLong.of(Long.MAX_VALUE));
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
		return new OutboxPurge(this.dataSource, this.transactionManager, acks,
				new IntegrationPurgeProperties(true, mode, Duration.ofDays(90), 3, Duration.ofSeconds(2), Duration.ofHours(1)));
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
