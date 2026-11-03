package com.geststock.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import com.geststock.platform.PlatformDb;
import com.geststock.support.PostgresTestConfiguration;
import com.geststock.tenancy.TenantContext;
import com.geststock.tenancy.TenantRef;

/**
 * Façade de la maintenance du flux ({@link FeedMaintenance}, lot 3, G20b ; décisions A et B ; plan § 33.3) sur la base
 * de l'entreprise de test : purge et séquencement contrôlés par le passage.
 *
 * <ul>
 * <li>Bail perdu : aucun nouveau lot, pas même le premier ; un lot déjà validé reste acquis. Budget épuisé : le premier
 * lot a quand même lieu, puis arrêt propre ({@link PassStop#LIMIT_REACHED}) ; jamais confondus.</li>
 * <li>Simulation : rien n'est supprimé, le repère ne bouge pas.</li>
 * <li>Séquencement : un lot plein n'est pas une erreur ; il continue tant que le budget et le bail le permettent, sinon
 * {@link PassStop#LIMIT_REACHED}.</li>
 * <li>Deux purges superposées, sans bail (mode {@code DELETE}, base de test) : aucune règle de G17 violée.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class FeedMaintenanceIT {

	private static final String FEED_STATE_ID = "00000000-0000-0000-0000-000000000001";

	@Autowired
	private DataSource dataSource;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private PlatformDb platform;

	private JdbcTemplate jdbc;

	private TenantRef company;

	@BeforeEach
	void setUp() {
		this.jdbc = new JdbcTemplate(this.dataSource);
		this.company = new TenantRef(
				this.platform.jdbc().sql("select id from platform.company where code = 'test'").query(UUID.class).single(),
				"test");
		resetFeed();
	}

	@AfterEach
	void tearDown() {
		resetFeed();
	}

	// --- Purge contrôlée par le passage (décision B) ------------------------------------------------------------

	@Test
	void aLostLeaseStartsNoBatchAtAll() {
		sequencedEvents(10);
		PurgeReport report = maintenance(PurgeMode.DELETE, 2).purge(control(false, true));
		assertThat(report.stop()).isEqualTo(PassStop.LEASE_LOST);
		assertThat(report.batches()).isZero();
		assertThat(report.deletedCount()).isZero();
		assertThat(purgedThrough()).isZero();
		assertThat(outboxSeqs()).hasSize(10);
	}

	@Test
	void aLeaseLostAfterTheFirstBatchStartsNoOtherAndKeepsTheValidatedOne() {
		sequencedEvents(10);
		AtomicInteger checks = new AtomicInteger();
		PassControl control = new PassControl() {

			@Override
			public boolean leaseHeld() {
				return checks.incrementAndGet() == 1;
			}

			@Override
			public boolean timeLeft() {
				return true;
			}

		};
		PurgeReport report = maintenance(PurgeMode.DELETE, 2).purge(control);
		assertThat(report.stop()).isEqualTo(PassStop.LEASE_LOST);
		assertThat(report.batches()).as("seul le lot commencé avec le bail").isEqualTo(1);
		assertThat(report.deletedCount()).isEqualTo(2);
		assertThat(purgedThrough()).as("lot validé acquis, repère sous verrou").isEqualTo(2);
		assertThat(outboxSeqs()).as("préfixe contigu supprimé").containsExactly(3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
	}

	@Test
	void anExhaustedBudgetStillRunsTheFirstBatchThenStopsWithoutBeingALostLease() {
		sequencedEvents(10);
		PurgeReport report = maintenance(PurgeMode.DELETE, 2).purge(control(true, false));
		assertThat(report.stop()).isEqualTo(PassStop.LIMIT_REACHED);
		assertThat(report.batches()).as("premier lot toujours exécuté").isEqualTo(1);
		assertThat(report.deletedCount()).isEqualTo(2);
		assertThat(report.purgedThroughAfter()).isLessThan(report.eligibleThroughSeq());
		assertThat(purgedThrough()).isEqualTo(2);
	}

	@Test
	void withinTheBudgetAndWithTheLeaseThePurgeCompletesUpToTheBound() {
		sequencedEvents(10);
		PurgeReport report = maintenance(PurgeMode.DELETE, 2).purge(control(true, true));
		assertThat(report.stop()).isEqualTo(PassStop.COMPLETE);
		assertThat(report.ceilingSeq()).isEqualTo(10);
		assertThat(report.eligibleThroughSeq()).as("plus grand seq conservé").isEqualTo(9);
		assertThat(report.deletedCount()).isEqualTo(9);
		assertThat(outboxSeqs()).containsExactly(10L);
		assertThat(purgedThrough()).isEqualTo(9);
	}

	@Test
	void simulationDeletesNothingAndNeverMovesTheWatermark() {
		sequencedEvents(10);
		PurgeReport report = maintenance(PurgeMode.SIMULATION, 2).purge(control(true, true));
		assertThat(report.mode()).isEqualTo(PurgeMode.SIMULATION);
		assertThat(report.stop()).isEqualTo(PassStop.COMPLETE);
		assertThat(report.eligibleCount()).isEqualTo(9);
		assertThat(report.deletedCount()).isZero();
		assertThat(report.purgedThroughAfter()).isZero();
		assertThat(purgedThrough()).isZero();
		assertThat(outboxSeqs()).hasSize(10);
	}

	// --- Séquencement (décision 3 de la validation) --------------------------------------------------------------

	@Test
	void fullSequencingBatchesContinueWhileTheBudgetAndTheLeaseAllow() {
		unsequencedEvents(7);
		SequencingOutcome outcome = maintenance(PurgeMode.SIMULATION, 2).sequence(control(true, true));
		assertThat(outcome.stop()).isEqualTo(PassStop.COMPLETE);
		assertThat(outcome.assigned()).isEqualTo(7);
		assertThat(lastSeq()).isEqualTo(7);
		assertThat(unsequencedCount()).isZero();
	}

	@Test
	void aFullBatchWhenTheBudgetIsExhaustedIsLimitReachedNotAnError() {
		unsequencedEvents(7);
		SequencingOutcome outcome = maintenance(PurgeMode.SIMULATION, 2).sequence(control(true, false));
		assertThat(outcome.stop()).isEqualTo(PassStop.LIMIT_REACHED);
		assertThat(outcome.assigned()).as("premier lot (plein) attribué").isEqualTo(3);
		assertThat(unsequencedCount()).isEqualTo(4);
	}

	@Test
	void aLostLeaseSequencesNothing() {
		unsequencedEvents(7);
		SequencingOutcome outcome = maintenance(PurgeMode.SIMULATION, 2).sequence(control(false, true));
		assertThat(outcome.stop()).isEqualTo(PassStop.LEASE_LOST);
		assertThat(outcome.assigned()).isZero();
		assertThat(unsequencedCount()).isEqualTo(7);
	}

	// --- Deux passages superposés, sans bail (G20-D12 : la sécurité ne dépend pas du bail) ------------------------

	@Test
	void twoOverlappingDeletePassesWithoutLeaseKeepEveryG17Rule() throws Exception {
		sequencedEvents(40);
		FeedMaintenance maintenance = maintenance(PurgeMode.DELETE, 1);
		CyclicBarrier start = new CyclicBarrier(3);
		ExecutorService threads = Executors.newFixedThreadPool(3);
		try {
			List<Future<PurgeReport>> passes = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				passes.add(threads.submit(() -> {
					start.await(10, TimeUnit.SECONDS);
					return TenantContext.callAs(this.company, () -> maintenance.purge(control(true, true)));
				}));
			}
			// Le flux continue d'avancer pendant les deux passages (séquenceur réel).
			Future<?> writer = threads.submit(() -> {
				start.await(10, TimeUnit.SECONDS);
				TenantContext.runAs(this.company, () -> {
					unsequencedEvents(5);
					maintenance.sequence(control(true, true));
				});
				return null;
			});
			writer.get(60, TimeUnit.SECONDS);
			long deleted = 0;
			long highestCeiling = 0;
			for (Future<PurgeReport> pass : passes) {
				PurgeReport report = pass.get(60, TimeUnit.SECONDS);
				assertThat(report.eligibleThroughSeq()).as("borne ≤ plafond P de ce passage")
					.isLessThanOrEqualTo(report.ceilingSeq());
				assertThat(report.purgedThroughAfter()).as("repère jamais en recul")
					.isGreaterThanOrEqualTo(report.purgedThroughBefore());
				deleted += report.deletedCount();
				highestCeiling = Math.max(highestCeiling, report.ceilingSeq());
			}
			List<Long> remaining = outboxSeqs();
			long watermark = purgedThrough();
			assertThat(deleted).as("chaque événement séquencé supprimé une seule fois").isEqualTo(lastSeq() - remaining.size());
			assertThat(remaining).as("suite contiguë au-dessus du repère").first().isEqualTo(watermark + 1);
			assertThat(remaining).last().as("plus grand seq conservé").isEqualTo(lastSeq());
			assertThat(remaining.get(remaining.size() - 1) - remaining.get(0) + 1).isEqualTo(remaining.size());
			assertThat(watermark).as("repère jamais au-delà du plafond P d'un passage").isLessThanOrEqualTo(highestCeiling);
		}
		finally {
			threads.shutdownNow();
		}
	}

	// --- Outils --------------------------------------------------------------------------------------------------

	/** Façade réelle, purge et séquenceur réels, lots de {@code batchSize} (séquenceur : lots de 3). */
	private FeedMaintenance maintenance(PurgeMode mode, int batchSize) {
		OutboxPurge purge = new OutboxPurge(this.dataSource, this.transactionManager, AckFloor::noLink,
				new IntegrationPurgeProperties(mode, Duration.ofDays(90), batchSize, Duration.ofSeconds(2), 100,
						Duration.ofSeconds(30)));
		FeedSequencer sequencer = new FeedSequencer(this.dataSource, this.transactionManager,
				new FeedSequencerProperties(3, Duration.ofSeconds(2), Duration.ofSeconds(5)));
		return new FeedMaintenance(purge, sequencer);
	}

	private static PassControl control(boolean leaseHeld, boolean timeLeft) {
		return new PassControl() {

			@Override
			public boolean leaseHeld() {
				return leaseHeld;
			}

			@Override
			public boolean timeLeft() {
				return timeLeft;
			}

		};
	}

	/** {@code count} événements séquencés, écrits il y a 100 jours (au-delà de la rétention de 90 jours). */
	private void sequencedEvents(int count) {
		unsequencedEvents(count);
		new FeedSequencer(this.dataSource, this.transactionManager,
				new FeedSequencerProperties(1000, Duration.ofSeconds(2), Duration.ofSeconds(5))).sequence();
	}

	private void unsequencedEvents(int count) {
		for (int i = 0; i < count; i++) {
			this.jdbc.update("""
					insert into integration_outbox (type, operation, object_id, object_version, created_at)
					values ('CATEGORY', 'UPSERT', ?, 1, now() - interval '100 days')""", UUID.randomUUID());
		}
	}

	private List<Long> outboxSeqs() {
		return this.jdbc.queryForList("select seq from integration_outbox where seq is not null order by seq", Long.class);
	}

	private int unsequencedCount() {
		return this.jdbc.queryForObject("select count(*) from integration_outbox where seq is null", Integer.class);
	}

	private long lastSeq() {
		return this.jdbc.queryForObject("select last_seq from integration_feed_state", Long.class);
	}

	private long purgedThrough() {
		return this.jdbc.queryForObject("select purged_through_seq from integration_feed_state", Long.class);
	}

	private void resetFeed() {
		this.jdbc.update("delete from integration_outbox");
		this.jdbc.update("delete from integration_link_ack");
		this.jdbc.update("""
				insert into integration_feed_state (id) values (?::uuid)
				on conflict (id) do update set last_seq = 0, purged_through_seq = 0""", FEED_STATE_ID);
	}

}
