package com.geststock.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.geststock.exception.ErrorCode;
import com.geststock.support.PostgresTestConfiguration;
import com.geststock.tenancy.DefaultTenant;
import com.geststock.tenancy.TenantDataSourceRegistry;
import com.zaxxer.hikari.HikariDataSource;

import tools.jackson.databind.json.JsonMapper;

/**
 * Lecture du flux d'intégration (contrat v10 § 12, § 20, P1, P2, P3, v9-D1) sur un vrai PostgreSQL. Les
 * événements sont écrits validés et sans {@code seq} ; chaque lecture appelle d'abord le séquenceur. La
 * disponibilité vient d'un faux fournisseur (implémentation réelle : étape 4c). Le flux est remis à zéro
 * avant et après chaque test.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class ChangeFeedServiceIT {

	private static final String FEED_STATE_ID = "00000000-0000-0000-0000-000000000001";

	@Autowired
	private FeedSequencer sequencer;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private TenantDataSourceRegistry registry;

	@Autowired
	private DefaultTenant defaultTenant;

	private JdbcTemplate jdbc;

	private FakeAvailability availability;

	private ChangeFeedService feed;

	@BeforeEach
	void resetFeed() {
		this.jdbc = new JdbcTemplate(this.dataSource);
		this.availability = new FakeAvailability(this.jdbc);
		this.feed = service(this.sequencer);
		reset();
	}

	@AfterEach
	void cleanFeed() {
		reset();
	}

	// --- Validation (T-C3-10) ------------------------------------------------------------------------

	@Test
	void theDefaultLimitIs100AndAtMost500AreServed() {
		insertCategories(501);
		ChangePage byDefault = this.feed.read(0, null, ChangeFilter.ALL);
		assertThat(byDefault.items()).hasSize(100);
		assertThat(byDefault.lastSeq()).isEqualTo(100L);
		assertThat(byDefault.hasMore()).isTrue();

		ChangePage widest = this.feed.read(0, 500, ChangeFilter.ALL);
		assertThat(widest.items()).hasSize(500);
		assertThat(widest.lastSeq()).isEqualTo(500L);
		assertThat(widest.hasMore()).isTrue();
	}

	@Test
	void aLimitOutside1To500OrANegativeAfterIsRefused() {
		insertCategories(1);
		for (int limit : new int[] { 0, 501, -1 }) {
			assertThatThrownBy(() -> this.feed.read(0, limit, ChangeFilter.ALL)).as("limit " + limit)
				.isInstanceOfSatisfying(ChangeFeedRequestException.class,
						e -> assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_PARAMETER));
		}
		assertThatThrownBy(() -> this.feed.read(-1, 10, ChangeFilter.ALL))
			.isInstanceOfSatisfying(ChangeFeedRequestException.class,
					e -> assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_PARAMETER));
		assertThat(sequencedCount()).as("validation avant toute attribution").isZero();
		assertThat(this.feed.read(0, 1, ChangeFilter.ALL).items()).hasSize(1);
	}

	// --- Séquenceur avant lecture ----------------------------------------------------------------------

	@Test
	void anEventCommittedJustBeforeTheCallIsServedInTheSamePage() {
		UUID category = insert(IntegrationEventType.CATEGORY, IntegrationOperation.UPSERT, 3, "{\"name\":\"Boissons\"}");
		assertThat(sequencedCount()).isZero();

		ChangePage page = this.feed.read(0, null, ChangeFilter.ALL);
		assertThat(page.items()).extracting(ChangeEvent::objectId).containsExactly(category);
		assertThat(page.lastSeq()).isEqualTo(1L);
		assertThat(page.hasMore()).isFalse();
	}

	@Test
	void aSkippedSequencerIsNotAnErrorAndAlreadySequencedEventsAreServed() throws Exception {
		insertCategories(2);
		this.feed.read(0, null, ChangeFilter.ALL);
		insertCategories(3);
		HikariDataSource pool = (HikariDataSource) this.registry.dataSource(this.defaultTenant.get().orElseThrow());
		try (Connection other = pool.getConnection()) {
			other.setAutoCommit(false);
			other.createStatement().execute("select last_seq from integration_feed_state for update");

			ChangePage page = this.feed.read(0, null, ChangeFilter.ALL);
			assertThat(page.items()).extracting(ChangeEvent::seq).containsExactly(1L, 2L);
			assertThat(page.lastSeq()).isEqualTo(2L);
			assertThat(page.hasMore()).as("3 événements validés attendent leur seq").isTrue();
			other.rollback();
		}
		assertThat(this.feed.read(2, null, ChangeFilter.ALL).items()).extracting(ChangeEvent::seq)
			.containsExactly(3L, 4L, 5L);
	}

	@Test
	void callingFromAnActiveTransactionFails() {
		insertCategories(1);
		TransactionTemplate transaction = new TransactionTemplate(this.transactionManager);
		assertThatThrownBy(() -> transaction.executeWithoutResult(status -> this.feed.read(0, null, ChangeFilter.ALL)))
			.isInstanceOf(IllegalTransactionStateException.class);
	}

	// --- Exposition des événements --------------------------------------------------------------------

	@Test
	void aWrittenEventIsExposedAsIs() {
		UUID product = insert(IntegrationEventType.PRODUCT, IntegrationOperation.UPSERT, 7,
				"{\"sku\":\"A-1\",\"referencePrice\":1500}");
		UUID archived = insert(IntegrationEventType.CATEGORY, IntegrationOperation.ARCHIVE, 4, null);

		List<ChangeEvent> items = this.feed.read(0, null, ChangeFilter.ALL).items();
		assertThat(items).hasSize(2);
		ChangeEvent upsert = items.get(0);
		assertThat(upsert.seq()).isEqualTo(1L);
		assertThat(upsert.type()).isEqualTo(IntegrationEventType.PRODUCT);
		assertThat(upsert.objectId()).isEqualTo(product);
		assertThat(upsert.version()).isEqualTo(7L);
		assertThat(upsert.operation()).isEqualTo(IntegrationOperation.UPSERT);
		assertThat(upsert.occurredAt()).isNotNull();
		assertThat(upsert.data().get("sku").asString()).isEqualTo("A-1");
		assertThat(upsert.data().get("referencePrice").asInt()).isEqualTo(1500);

		ChangeEvent archive = items.get(1);
		assertThat(archive.objectId()).isEqualTo(archived);
		assertThat(archive.operation()).isEqualTo(IntegrationOperation.ARCHIVE);
		assertThat(archive.version()).isEqualTo(4L);
		assertThat(archive.data()).isNull();
	}

	// --- Filtre (T-C3-7) -------------------------------------------------------------------------------

	@Test
	void filteredEventsGiveAnEmptyPageButLastSeqAdvances() {
		insertCategories(3);
		ChangePage page = this.feed.read(0, null, (type, objectId) -> false);
		assertThat(page.items()).isEmpty();
		assertThat(page.lastSeq()).isEqualTo(3L);
		assertThat(page.hasMore()).isFalse();
	}

	@Test
	void lastSeqIsTheLastExaminedEventEvenWhenFiltered() {
		UUID kept = insert(IntegrationEventType.PRODUCT, IntegrationOperation.UPSERT, 1, "{}");
		insertCategories(2);
		ChangePage page = this.feed.read(0, 2, (type, objectId) -> type == IntegrationEventType.PRODUCT);
		assertThat(page.items()).extracting(ChangeEvent::objectId).containsExactly(kept);
		assertThat(page.lastSeq()).isEqualTo(2L);
		assertThat(page.hasMore()).isTrue();
	}

	@Test
	void nothingToExamineReturnsAfterAsLastSeq() {
		insertCategories(2);
		ChangePage page = this.feed.read(2, null, ChangeFilter.ALL);
		assertThat(page.items()).isEmpty();
		assertThat(page.lastSeq()).isEqualTo(2L);
		assertThat(page.hasMore()).isFalse();
	}

	// --- hasMore (T-C3-9) ------------------------------------------------------------------------------

	@Test
	void hasMoreIsTrueWhenSequencedEventsRemainBeyondLastSeq() {
		insertCategories(5);
		ChangePage page = this.feed.read(0, 2, ChangeFilter.ALL);
		assertThat(page.lastSeq()).isEqualTo(2L);
		assertThat(page.hasMore()).isTrue();
	}

	@Test
	void hasMoreIsTrueWhenCommittedEventsAwaitTheirSeq() {
		insertCategories(3);
		ChangeFeedService oneAtATime = service(new FeedSequencer(this.dataSource, this.transactionManager,
				new FeedSequencerProperties(1, Duration.ofMillis(1500), Duration.ofMillis(4500))));
		ChangePage page = oneAtATime.read(0, 500, ChangeFilter.ALL);
		assertThat(page.items()).extracting(ChangeEvent::seq).containsExactly(1L);
		assertThat(page.lastSeq()).isEqualTo(1L);
		assertThat(page.hasMore()).as("2 événements validés sans seq").isTrue();
	}

	@Test
	void hasMoreIsFalseWhenEverythingWasExamined() {
		insertCategories(3);
		ChangePage page = this.feed.read(0, 3, ChangeFilter.ALL);
		assertThat(page.lastSeq()).isEqualTo(3L);
		assertThat(page.hasMore()).isFalse();
	}

	// --- Curseurs (T-C3-14, curseur en avance) -----------------------------------------------------------

	@Test
	void aCursorBelowThePurgeMarkExpiresAndReplayFromTheMarkLosesNothing() {
		insertCategories(6);
		this.feed.read(0, null, ChangeFilter.ALL);
		purgeThrough(3);

		assertThatThrownBy(() -> this.feed.read(2, null, ChangeFilter.ALL))
			.isInstanceOfSatisfying(CursorExpiredException.class, e -> {
				assertThat(e.getCode()).isEqualTo(ErrorCode.CURSOR_EXPIRED);
				assertThat(e.getStatus().value()).isEqualTo(410);
				assertThat(e.getAfter()).isEqualTo(2L);
				assertThat(e.getPurgedThroughSeq()).isEqualTo(3L);
			});
		assertThat(this.feed.read(3, null, ChangeFilter.ALL).items()).extracting(ChangeEvent::seq)
			.containsExactly(4L, 5L, 6L);

		// Instantané simulé (étape 7) : son asOfSeq est le last_seq validé au moment de l'instantané.
		long asOfSeq = this.jdbc.queryForObject("select last_seq from integration_feed_state", Long.class);
		insertCategories(2);
		ChangePage resumed = this.feed.read(asOfSeq, null, ChangeFilter.ALL);
		assertThat(resumed.items()).extracting(ChangeEvent::seq).containsExactly(7L, 8L);
		assertThat(resumed.hasMore()).isFalse();
	}

	@Test
	void aCursorAheadOfTheLastSeqIsAnExplicitError() {
		insertCategories(3);
		assertThatThrownBy(() -> this.feed.read(4, null, ChangeFilter.ALL))
			.isInstanceOfSatisfying(CursorAheadException.class, e -> {
				assertThat(e.getAfter()).isEqualTo(4L);
				assertThat(e.getLastSeq()).isEqualTo(3L);
			});
		assertThat(this.feed.read(3, null, ChangeFilter.ALL).items()).isEmpty();
	}

	// --- Rejeu (T-C3-2d) --------------------------------------------------------------------------------

	@Test
	void theSameAfterTwiceGivesIdenticalPages() {
		UUID product = UUID.randomUUID();
		this.availability.set(product, 12);
		insertCategories(2);
		insert(IntegrationEventType.AVAILABILITY, IntegrationOperation.UPSERT, product, 0, null);
		insert(IntegrationEventType.PRODUCT, IntegrationOperation.UPSERT, 2, "{\"sku\":\"B-2\"}");

		ChangePage first = this.feed.read(1, null, ChangeFilter.ALL);
		ChangePage second = this.feed.read(1, null, ChangeFilter.ALL);
		assertThat(second).isEqualTo(first);
		assertThat(first.items()).extracting(ChangeEvent::seq).containsExactly(2L, 3L, 4L);
	}

	// --- Événements minces AVAILABILITY (P3) -------------------------------------------------------------

	@Test
	void availabilityEventsOfOneProductAreMergedIntoTheHighestSeq() {
		UUID p1 = UUID.randomUUID();
		UUID p2 = UUID.randomUUID();
		this.availability.set(p1, 5);
		this.availability.set(p2, 9);
		insert(IntegrationEventType.AVAILABILITY, IntegrationOperation.UPSERT, p1, 0, null); // 1
		UUID category = insert(IntegrationEventType.CATEGORY, IntegrationOperation.UPSERT, 1, "{}"); // 2
		insert(IntegrationEventType.AVAILABILITY, IntegrationOperation.UPSERT, p1, 0, null); // 3
		insert(IntegrationEventType.AVAILABILITY, IntegrationOperation.UPSERT, p2, 0, null); // 4
		insert(IntegrationEventType.AVAILABILITY, IntegrationOperation.UPSERT, p1, 0, null); // 5

		ChangePage page = this.feed.read(0, null, ChangeFilter.ALL);
		assertThat(page.items()).extracting(ChangeEvent::seq).containsExactly(2L, 4L, 5L);
		assertThat(page.items()).extracting(ChangeEvent::objectId).containsExactly(category, p2, p1);
		ChangeEvent merged = page.items().get(2);
		assertThat(merged.type()).isEqualTo(IntegrationEventType.AVAILABILITY);
		assertThat(merged.version()).as("version = seq (P3)").isEqualTo(5L);
		assertThat(merged.data().get("productId").asString()).isEqualTo(p1.toString());
		assertThat(merged.data().get("available").asInt()).isEqualTo(5);
		assertThat(page.items().get(1).version()).isEqualTo(4L);
		assertThat(page.lastSeq()).isEqualTo(5L);
		assertThat(this.availability.calls).as("une valeur par produit servi").containsExactlyInAnyOrder(p1, p2);
	}

	@Test
	void availabilityIsComputedInTheReadOnlyRepeatableReadTransactionOfThePage() {
		UUID product = UUID.randomUUID();
		this.availability.set(product, 1);
		insert(IntegrationEventType.AVAILABILITY, IntegrationOperation.UPSERT, product, 0, null);
		this.feed.read(0, null, ChangeFilter.ALL);
		assertThat(this.availability.isolation).isEqualTo("repeatable read");
		assertThat(this.availability.readOnly).isEqualTo("on");
	}

	@Test
	void aFilteredAvailabilityEventIsNotComputed() {
		UUID product = UUID.randomUUID();
		insert(IntegrationEventType.AVAILABILITY, IntegrationOperation.UPSERT, product, 0, null);
		ChangePage page = this.feed.read(0, null, (type, objectId) -> type != IntegrationEventType.AVAILABILITY);
		assertThat(page.items()).isEmpty();
		assertThat(page.lastSeq()).isEqualTo(1L);
		assertThat(this.availability.calls).isEmpty();
	}

	// --- Outils ------------------------------------------------------------------------------------------

	private ChangeFeedService service(FeedSequencer feedSequencer) {
		return new ChangeFeedService(feedSequencer, this.dataSource, this.transactionManager, this.availability,
				this.jsonMapper);
	}

	private void reset() {
		this.jdbc.update("delete from integration_outbox");
		this.jdbc.update("""
				insert into integration_feed_state (id) values (?::uuid)
				on conflict (id) do update set last_seq = 0, purged_through_seq = 0""", FEED_STATE_ID);
	}

	private void insertCategories(int count) {
		for (int i = 0; i < count; i++) {
			insert(IntegrationEventType.CATEGORY, IntegrationOperation.UPSERT, 1, "{}");
		}
	}

	private UUID insert(IntegrationEventType type, IntegrationOperation operation, long version, String payload) {
		return insert(type, operation, UUID.randomUUID(), version, payload);
	}

	private UUID insert(IntegrationEventType type, IntegrationOperation operation, UUID objectId, long version,
			String payload) {
		this.jdbc.update("""
				insert into integration_outbox (type, operation, object_id, object_version, payload)
				values (?, ?, ?, ?, cast(? as jsonb))""", type.name(), operation.name(), objectId, version, payload);
		return objectId;
	}

	private void purgeThrough(long seq) {
		this.jdbc.update("delete from integration_outbox where seq <= ?", seq);
		this.jdbc.update("update integration_feed_state set purged_through_seq = ?", seq);
	}

	private int sequencedCount() {
		return this.jdbc.queryForObject("select count(*) from integration_outbox where seq is not null", Integer.class);
	}

	/** Faux fournisseur : valeur fixée par le test, appels et transaction englobante observés. */
	private static final class FakeAvailability implements AvailabilityProvider {

		private final JdbcTemplate jdbc;

		private final Map<UUID, Integer> values = new HashMap<>();

		private final List<UUID> calls = new ArrayList<>();

		private String isolation;

		private String readOnly;

		FakeAvailability(JdbcTemplate jdbc) {
			this.jdbc = jdbc;
		}

		void set(UUID productId, int available) {
			this.values.put(productId, available);
		}

		@Override
		public Object availability(UUID productId) {
			this.calls.add(productId);
			this.isolation = this.jdbc.queryForObject("show transaction_isolation", String.class);
			this.readOnly = this.jdbc.queryForObject("show transaction_read_only", String.class);
			return Map.of("productId", productId, "available", this.values.getOrDefault(productId, 0),
					"computedAt", "2026-01-01T00:00:00Z");
		}

	}

}
