package com.geststock.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.geststock.support.PostgresTestConfiguration;
import com.geststock.tenancy.DefaultTenant;
import com.geststock.tenancy.TenantDataSourceRegistry;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Séquenceur du flux d'intégration (conception D, § 20.2) sur un vrai PostgreSQL : attribution des
 * {@code seq} aux seuls événements validés, dans l'ordre d'écriture, sans saut ni doublon, y compris
 * sous concurrence. Les scénarios concurrents utilisent de vraies connexions JDBC distinctes, prises
 * dans le pool de l'entreprise de test. Le flux est remis à zéro avant et après chaque test.
 *
 * <p>T-C3-1 et T-C3-2b sont aussi répétés 100 fois (étiquette {@code repeated}, environ 6 s) : inclus dans
 * le {@code verify} standard ; exclusion possible par {@code -DexcludedGroups=repeated}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class FeedSequencerIT {

	private static final String FEED_STATE_ID = "00000000-0000-0000-0000-000000000001";

	@Autowired
	private FeedSequencer sequencer;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private TenantDataSourceRegistry registry;

	@Autowired
	private DefaultTenant defaultTenant;

	private JdbcTemplate jdbc;

	private HikariDataSource pool;

	@BeforeEach
	void resetFeed() {
		this.jdbc = new JdbcTemplate(this.dataSource);
		this.pool = (HikariDataSource) this.registry.dataSource(this.defaultTenant.get().orElseThrow());
		reset();
	}

	@AfterEach
	void cleanFeed() {
		reset();
	}

	// --- T-C3-1 et T-C3-2 ------------------------------------------------------------------------

	@Test
	void aLateCommitIsSequencedAfterwardsWithoutGap() throws Exception {
		lateCommitScenario();
	}

	@Test
	void anUncommittedEventReceivesNoSeq() throws Exception {
		try (Connection writer = openTransaction()) {
			UUID event = insertEvent(writer);
			SequencingResult result = this.sequencer.sequence();
			assertThat(result.assigned()).isZero();
			assertThat(result.skipped()).isFalse();
			assertThat(seqSeenBy(writer, event)).as("seq de l'événement non validé").isNull();
			writer.rollback();
		}
		assertThat(lastSeq()).isZero();
	}

	@Test
	void twoConcurrentSequencersNeitherDuplicateNorSkip() throws Exception {
		concurrentSequencersScenario();
	}

	@Test
	void aFailureBetweenTheTwoUpdatesAssignsNothing() {
		insertCommittedEvents(3);
		FeedSequencer failing = new FeedSequencer(this.dataSource, this.transactionManager, properties(1000), () -> {
			throw new IllegalStateException("échec injecté entre les deux UPDATE");
		});
		assertThatThrownBy(failing::sequence).hasMessageContaining("échec injecté");
		assertThat(sequencedCount()).isZero();
		assertThat(lastSeq()).isZero();

		SequencingResult next = this.sequencer.sequence();
		assertThat(next.assigned()).isEqualTo(3);
		assertThat(seqsInWriteOrder()).containsExactly(1L, 2L, 3L);
		assertThat(lastSeq()).isEqualTo(3L);
	}

	// --- Ligne d'état ----------------------------------------------------------------------------

	@Test
	void aMissingFeedStateRowIsReportedExplicitly() {
		insertCommittedEvents(2);
		this.jdbc.update("delete from integration_feed_state");
		try {
			assertThatThrownBy(this.sequencer::sequence).isInstanceOf(IntegrationFeedStateMissingException.class)
				.hasMessageContaining("COALESCE(MAX(seq), 0)");
			assertThat(sequencedCount()).isZero();
		}
		finally {
			// Procédure de réparation documentée : last_seq = COALESCE(MAX(seq), 0).
			this.jdbc.update("""
					insert into integration_feed_state (id, last_seq, purged_through_seq)
					select ?::uuid, coalesce(max(seq), 0), 0 from integration_outbox""", FEED_STATE_ID);
		}
		assertThat(this.sequencer.sequence().assigned()).isEqualTo(2);
	}

	@Test
	void aRowLockedByAnotherConnectionIsSkippedWithoutWaiting() throws Exception {
		insertCommittedEvents(2);
		this.jdbc.update("update integration_feed_state set last_seq = 7");
		try (Connection holder = openTransaction();
				PreparedStatement lock = holder.prepareStatement("select last_seq from integration_feed_state for update")) {
			lock.executeQuery().close();
			long start = System.nanoTime();
			SequencingResult result = this.sequencer.sequence();
			long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
			assertThat(result.skipped()).isTrue();
			assertThat(result.assigned()).isZero();
			assertThat(result.lastSeq()).isEqualTo(7L);
			assertThat(elapsedMs).as("aucune attente du verrou").isLessThan(1000L);
			holder.rollback();
		}
		assertThat(sequencedCount()).isZero();
	}

	// --- Transaction ---------------------------------------------------------------------------

	@Test
	void callingFromAnActiveTransactionFailsAndAssignsNothing() {
		insertCommittedEvents(2);
		TransactionTemplate tx = new TransactionTemplate(this.transactionManager);
		assertThatThrownBy(() -> tx.executeWithoutResult(status -> this.sequencer.sequence()))
			.isInstanceOf(IllegalTransactionStateException.class);
		assertThat(sequencedCount()).isZero();
		assertThat(lastSeq()).isZero();
	}

	@Test
	void theSequencerUsesASingleConnectionWithItsTimeouts() {
		insertCommittedEvents(1);
		AtomicReference<Integer> active = new AtomicReference<>();
		AtomicReference<String> lockTimeout = new AtomicReference<>();
		AtomicReference<String> statementTimeout = new AtomicReference<>();
		FeedSequencer observed = new FeedSequencer(this.dataSource, this.transactionManager, properties(1000), () -> {
			active.set(this.pool.getHikariPoolMXBean().getActiveConnections());
			// Même connexion que le séquenceur (transaction liée au fil d'exécution).
			lockTimeout.set(this.jdbc.queryForObject("show lock_timeout", String.class));
			statementTimeout.set(this.jdbc.queryForObject("show statement_timeout", String.class));
		});
		assertThat(observed.sequence().assigned()).isEqualTo(1);
		assertThat(active.get()).as("connexions actives pendant l'attribution").isEqualTo(1);
		assertThat(lockTimeout.get()).isEqualTo("1500ms");
		assertThat(statementTimeout.get()).isEqualTo("4500ms");
		assertThat(this.pool.getHikariPoolMXBean().getActiveConnections()).as("connexion rendue").isZero();
	}

	// --- Lot et ordre ----------------------------------------------------------------------------

	@Test
	void theBatchLimitSplitsTheWork() {
		insertCommittedEvents(5);
		FeedSequencer small = new FeedSequencer(this.dataSource, this.transactionManager, properties(3));
		SequencingResult first = small.sequence();
		assertThat(first.assigned()).isEqualTo(3);
		assertThat(first.limitReached()).isTrue();
		assertThat(first.lastSeq()).isEqualTo(3L);
		SequencingResult second = small.sequence();
		assertThat(second.assigned()).isEqualTo(2);
		assertThat(second.limitReached()).isFalse();
		assertThat(second.lastSeq()).isEqualTo(5L);
		assertThat(seqsInWriteOrder()).containsExactly(1L, 2L, 3L, 4L, 5L);
	}

	@Test
	void seqsStartAfterLastSeqAndFollowTheWriteOrder() {
		this.jdbc.update("update integration_feed_state set last_seq = 10");
		insertCommittedEvents(4);
		SequencingResult result = this.sequencer.sequence();
		assertThat(result.assigned()).isEqualTo(4);
		assertThat(result.lastSeq()).isEqualTo(14L);
		assertThat(seqsInWriteOrder()).containsExactly(11L, 12L, 13L, 14L);
		assertThat(lastSeq()).isEqualTo(14L);
	}

	@Test
	void nothingToSequenceLeavesTheStateUntouched() {
		SequencingResult result = this.sequencer.sequence();
		assertThat(result).isEqualTo(new SequencingResult(0, 0L, false, false));
		assertThat(lastSeq()).isZero();
	}

	// --- Répétitions (opt-in) --------------------------------------------------------------------

	@RepeatedTest(100)
	@Tag("repeated")
	void lateCommitRepeated() throws Exception {
		lateCommitScenario();
	}

	@RepeatedTest(100)
	@Tag("repeated")
	void concurrentSequencersRepeated() throws Exception {
		concurrentSequencersScenario();
	}

	// --- Scénarios ---------------------------------------------------------------------------------

	/** T-C3-1 : T2 démarre et écrit ; T1 démarre, écrit et valide ; séquencement ; T2 valide ; séquencement. */
	private void lateCommitScenario() throws Exception {
		UUID late;
		UUID early;
		try (Connection t2 = openTransaction(); Connection t1 = openTransaction()) {
			late = insertEvent(t2);
			early = insertEvent(t1);
			t1.commit();

			SequencingResult first = this.sequencer.sequence();
			assertThat(first.assigned()).isEqualTo(1);
			assertThat(seqOf(early)).isEqualTo(1L);
			assertThat(seqSeenBy(t2, late)).as("T2 non validée : pas de seq").isNull();

			t2.commit();
		}
		SequencingResult second = this.sequencer.sequence();
		assertThat(second.assigned()).isEqualTo(1);
		assertThat(seqOf(late)).as("événement validé tard : seq après celui déjà servi").isEqualTo(2L);
		assertThat(lastSeq()).isEqualTo(2L);
		assertThat(allSeqs()).containsExactly(1L, 2L);
		reset();
	}

	/** T-C3-2b : deux séquenceurs lancés ensemble (barrière). */
	private void concurrentSequencersScenario() throws Exception {
		int events = 20;
		insertCommittedEvents(events);
		CyclicBarrier barrier = new CyclicBarrier(2);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			List<Future<SequencingResult>> futures = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				futures.add(executor.submit(() -> {
					barrier.await(10, TimeUnit.SECONDS);
					return this.sequencer.sequence();
				}));
			}
			List<SequencingResult> results = new ArrayList<>();
			for (Future<SequencingResult> future : futures) {
				results.add(future.get(30, TimeUnit.SECONDS));
			}
			assertThat(results.stream().mapToInt(SequencingResult::assigned).sum()).isEqualTo(events);
			assertThat(results.stream().filter(SequencingResult::skipped).count()).isLessThanOrEqualTo(1);
		}
		finally {
			executor.shutdownNow();
		}
		List<Long> seqs = allSeqs();
		assertThat(seqs).hasSize(events).doesNotHaveDuplicates();
		assertThat(seqs.get(0)).isEqualTo(1L);
		assertThat(seqs.get(events - 1)).isEqualTo((long) events);
		assertThat(lastSeq()).isEqualTo((long) events);
		reset();
	}

	// --- Outils ------------------------------------------------------------------------------------

	private static FeedSequencerProperties properties(int batchSize) {
		return new FeedSequencerProperties(batchSize, Duration.ofMillis(1500), Duration.ofMillis(4500));
	}

	private void reset() {
		this.jdbc.update("delete from integration_outbox");
		this.jdbc.update("""
				insert into integration_feed_state (id) values (?::uuid)
				on conflict (id) do update set last_seq = 0, purged_through_seq = 0""", FEED_STATE_ID);
	}

	private Connection openTransaction() throws SQLException {
		Connection connection = this.pool.getConnection();
		connection.setAutoCommit(false);
		return connection;
	}

	private static UUID insertEvent(Connection connection) throws SQLException {
		UUID objectId = UUID.randomUUID();
		try (PreparedStatement insert = connection.prepareStatement("""
				insert into integration_outbox (type, operation, object_id, object_version) values ('CATEGORY', 'UPSERT', ?, 0)""")) {
			insert.setObject(1, objectId);
			insert.executeUpdate();
		}
		return objectId;
	}

	private void insertCommittedEvents(int count) {
		for (int i = 0; i < count; i++) {
			this.jdbc.update("insert into integration_outbox (type, operation, object_id, object_version) values ('CATEGORY', 'UPSERT', ?, 0)",
					UUID.randomUUID());
		}
	}

	private static Long seqSeenBy(Connection connection, UUID objectId) throws SQLException {
		try (PreparedStatement select = connection.prepareStatement("select seq from integration_outbox where object_id = ?")) {
			select.setObject(1, objectId);
			try (ResultSet rs = select.executeQuery()) {
				rs.next();
				long seq = rs.getLong(1);
				return rs.wasNull() ? null : seq;
			}
		}
	}

	private Long seqOf(UUID objectId) {
		return this.jdbc.queryForObject("select seq from integration_outbox where object_id = ?", Long.class, objectId);
	}

	private List<Long> seqsInWriteOrder() {
		return this.jdbc.queryForList("select seq from integration_outbox order by write_id", Long.class);
	}

	private List<Long> allSeqs() {
		return this.jdbc.queryForList("select seq from integration_outbox where seq is not null order by seq", Long.class);
	}

	private int sequencedCount() {
		return this.jdbc.queryForObject("select count(*) from integration_outbox where seq is not null", Integer.class);
	}

	private long lastSeq() {
		return this.jdbc.queryForObject("select last_seq from integration_feed_state", Long.class);
	}

}
