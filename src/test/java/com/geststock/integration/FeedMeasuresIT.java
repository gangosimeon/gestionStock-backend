package com.geststock.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.geststock.integration.FeedMeasures.LinkFeedMeasures;
import com.geststock.support.PostgresTestConfiguration;
import com.geststock.tenancy.DefaultTenant;
import com.geststock.tenancy.TenantContext;
import com.geststock.tenancy.TenantRef;

/**
 * Mesures du flux calculées à la demande par la maintenance (lot 3, G20c ; G20-D7, plan § 33.5), sur un vrai
 * PostgreSQL : événements sans {@code seq} au niveau de l'entreprise seulement ; par liaison qui a acquitté, événements
 * séquencés en attente, âge du plus ancien, retard et temps depuis l'acquittement, jamais additionnés entre liaisons ;
 * requêtes servies par des index. Les anciennes jauges en mémoire et leur tâche planifiée sont retirées (G20-D10).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class FeedMeasuresIT {

	private static final String FEED_STATE_ID = "00000000-0000-0000-0000-000000000001";

	@Autowired
	private FeedMaintenance feed;

	@Autowired
	private DefaultTenant defaultTenant;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private ApplicationContext context;

	private TenantRef tenant;

	@BeforeEach
	void setUp() {
		this.tenant = this.defaultTenant.get().orElseThrow();
		resetFeed();
	}

	@AfterEach
	void tearDown() {
		resetFeed();
	}

	// --- Entreprise ------------------------------------------------------------------------------------------------

	@Test
	void theCompanyMeasuresGiveTheCountAndAgeOfUnsequencedEvents() {
		insertUnsequenced(3, Duration.ofMinutes(10));
		insertSequenced(1, Duration.ofDays(1));
		this.jdbc.update("update integration_feed_state set last_seq = 1");

		FeedMeasures measures = measure(Set.of());
		assertThat(measures.unsequenced()).isEqualTo(3);
		assertThat(measures.oldestUnsequencedSeconds()).isBetween(600L, 660L);
		assertThat(measures.lastSeq()).isEqualTo(1);
		assertThat(measures.purgedThroughSeq()).isZero();
		assertThat(measures.links()).isEmpty();
	}

	@Test
	void noUnsequencedEventMeansZeroCountAndZeroAge() {
		insertSequenced(1, Duration.ofDays(1));
		FeedMeasures measures = measure(Set.of());
		assertThat(measures.unsequenced()).isZero();
		assertThat(measures.oldestUnsequencedSeconds()).isZero();
	}

	// --- Liaisons --------------------------------------------------------------------------------------------------

	@Test
	void eachLinkHasItsOwnPendingEventsAndUnsequencedEventsAreCountedOnceForTheCompany() {
		// seq 1 à 6 : seq n écrit il y a (10 - n) heures ; created_at n'est pas monotone en général, ici il l'est.
		for (int seq = 1; seq <= 6; seq++) {
			insertSequenced(seq, Duration.ofHours(10 - seq));
		}
		insertUnsequenced(2, Duration.ofMinutes(10));
		this.jdbc.update("update integration_feed_state set last_seq = 6, purged_through_seq = 0");
		UUID behind = UUID.randomUUID();
		UUID upToDate = UUID.randomUUID();
		UUID neverAcknowledged = UUID.randomUUID();
		acknowledge(behind, 2, Duration.ofHours(1));
		acknowledge(upToDate, 6, Duration.ZERO);

		FeedMeasures measures = measure(Set.of(behind, upToDate, neverAcknowledged));

		assertThat(measures.unsequenced()).as("au niveau de l'entreprise, une seule fois").isEqualTo(2);
		assertThat(measures.lastSeq()).isEqualTo(6);
		assertThat(measures.links()).as("liaison sans acquittement absente").containsOnlyKeys(behind, upToDate);
		LinkFeedMeasures late = measures.links().get(behind);
		assertThat(late.acknowledgedSeq()).isEqualTo(2);
		assertThat(late.sinceAcknowledgedSeconds()).isBetween(3600L, 3660L);
		assertThat(late.pending()).as("seq 3 à 6, sans les événements sans seq").isEqualTo(4);
		assertThat(late.oldestPendingSeconds()).as("seq 3, écrit il y a 7 h").isBetween(7 * 3600L, 7 * 3600L + 60);
		assertThat(late.lag()).isEqualTo(4);
		assertThat(measures.links().get(upToDate)).isEqualTo(new LinkFeedMeasures(6, measures.links().get(upToDate)
			.sinceAcknowledgedSeconds(), 0, 0, 0));
		assertThat(measures.links().get(upToDate).sinceAcknowledgedSeconds()).isBetween(0L, 60L);
	}

	// --- Plans des requêtes ------------------------------------------------------------------------------------

	/**
	 * La requête des événements sans {@code seq} ne parcourt jamais toute la table. Deux index le peuvent, selon les
	 * statistiques de la table (moment de l'autoanalyse pendant la suite) : l'index partiel
	 * {@code ix_integration_outbox_unsequenced}, ou la partie NULL de l'index unique {@code uk_integration_outbox_seq}.
	 * Les deux états sont vérifiés, le second rendu déterministe par {@code ANALYZE}.
	 */
	@Test
	void theUnsequencedQueryReadsOnlyUnsequencedEventsThroughAnIndex() {
		assertReadsOnlyUnsequencedEvents(plan(FeedHealth.UNSEQUENCED));

		this.jdbc.update("""
				insert into integration_outbox (type, operation, object_id, object_version, seq, created_at)
				select 'CATEGORY', 'UPSERT', gen_random_uuid(), 1, g, now() - interval '1 day' from generate_series(1, 2000) g""");
		insertUnsequenced(35, Duration.ofMinutes(1));
		this.jdbc.execute("analyze integration_outbox");
		assertReadsOnlyUnsequencedEvents(plan(FeedHealth.UNSEQUENCED));
	}

	/** Les événements en attente d'une liaison sont lus par l'index unique sur {@code seq}, à partir de l'acquittement. */
	@Test
	void thePendingEventsOfALinkAreReadThroughTheSeqIndex() {
		String plan = plan(FeedHealth.LINKS.replace(":links", "'" + UUID.randomUUID() + "'::uuid"));
		assertThat(plan).as("aucun parcours séquentiel").doesNotContain("Seq Scan");
		assertThat(plan).as("index unique sur seq, borné par l'acquittement").contains("uk_integration_outbox_seq")
			.contains("seq > a.acknowledged_seq");
	}

	// --- Anciennes tâches (G20-D10) --------------------------------------------------------------------------------

	@Test
	void theInMemoryGaugesAndTheirScheduledJobAreGone() {
		assertThat(this.context.containsBean("feedMetrics")).isFalse();
		assertThat(this.context.containsBean("feedMetricsJob")).isFalse();
		assertThat(this.context.getBeansWithAnnotation(EnableScheduling.class)).as("aucun planificateur").isEmpty();
	}

	// --- Outils ------------------------------------------------------------------------------------------

	private FeedMeasures measure(Set<UUID> links) {
		return TenantContext.callAs(this.tenant, () -> this.feed.measure(links));
	}

	/** Plan d'une requête, parcours séquentiel découragé (sur une petite table, il serait légitime). */
	private String plan(String sql) {
		List<String> plan = this.transactions.execute(status -> {
			this.jdbc.execute("set local enable_seqscan = off");
			return this.jdbc.queryForList("explain " + sql, String.class);
		});
		System.out.println("Plan :\n" + String.join("\n", plan));
		return String.join("\n", plan);
	}

	private static void assertReadsOnlyUnsequencedEvents(String plan) {
		assertThat(plan).as("aucun parcours séquentiel de la table").doesNotContain("Seq Scan");
		assertThat(plan).as("aucun filtrage après lecture").doesNotContain("Filter:");
		assertThat(plan).as("seuls les événements sans seq sont lus, par un index").satisfiesAnyOf(
				p -> assertThat(p).contains("ix_integration_outbox_unsequenced"),
				p -> assertThat(p).contains("uk_integration_outbox_seq").contains("Index Cond: (seq IS NULL)"));
	}

	private void insertUnsequenced(int count, Duration age) {
		for (int i = 0; i < count; i++) {
			this.jdbc.update("""
					insert into integration_outbox (type, operation, object_id, object_version, created_at)
					values ('CATEGORY', 'UPSERT', ?, 1, now() - make_interval(secs => ?))""",
					UUID.randomUUID(), (double) age.toSeconds());
		}
	}

	private void insertSequenced(long seq, Duration age) {
		this.jdbc.update("""
				insert into integration_outbox (type, operation, object_id, object_version, seq, created_at)
				values ('CATEGORY', 'UPSERT', ?, 1, ?, now() - make_interval(secs => ?))""",
				UUID.randomUUID(), seq, (double) age.toSeconds());
	}

	private void acknowledge(UUID link, long seq, Duration age) {
		this.jdbc.update("""
				insert into integration_link_ack (link_id, acknowledged_seq, acknowledged_at)
				values (?, ?, now() - make_interval(secs => ?))""", link, seq, (double) age.toSeconds());
	}

	private void resetFeed() {
		this.jdbc.update("delete from integration_outbox");
		this.jdbc.update("delete from integration_link_ack");
		this.jdbc.update("""
				insert into integration_feed_state (id) values (?::uuid)
				on conflict (id) do update set last_seq = 0, purged_through_seq = 0""", FEED_STATE_ID);
	}

}
