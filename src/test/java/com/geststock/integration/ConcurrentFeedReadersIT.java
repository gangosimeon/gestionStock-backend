package com.geststock.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.LongStream;

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

import com.geststock.support.PostgresTestConfiguration;

/**
 * T-C3-2 b : deux lecteurs du flux lancés en même temps, sur de vraies connexions et derrière une barrière (plan
 * § 20.4 : « mêmes ensembles, ni doublon ni trou »). L'un attribue les {@code seq}, l'autre attend ou sert ceux déjà
 * attribués ({@code SKIP LOCKED}) ; chaque page est contiguë à partir de {@code after + 1}, chaque événement reçoit
 * exactement un {@code seq}, et la lecture suivante sert le reste sans perte. Variante avec un écrivain concurrent.
 * Le scénario principal est aussi répété 100 fois (étiquette {@code repeated}, exclusion possible par
 * {@code -DexcludedGroups=repeated}).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class ConcurrentFeedReadersIT {

	/** Liaison qui lit le flux (G4a : la disponibilité servie est celle de ses dépôts). */
	private static final LinkRef LINK = new LinkRef(UUID.randomUUID());

	private static final String FEED_STATE_ID = "00000000-0000-0000-0000-000000000001";

	private static final int EVENTS = 30;

	@Autowired
	private ChangeFeedService feed;

	@Autowired
	private DataSource dataSource;

	private JdbcTemplate jdbc;

	private ExecutorService threads;

	@BeforeEach
	void setUp() {
		this.jdbc = new JdbcTemplate(this.dataSource);
		this.threads = Executors.newFixedThreadPool(3);
		resetFeed();
	}

	@AfterEach
	void tearDown() {
		this.threads.shutdownNow();
		resetFeed();
	}

	@Test
	void twoConcurrentReadersNeitherDuplicateNorSkip() throws Exception {
		twoReadersScenario();
	}

	@RepeatedTest(100)
	@Tag("repeated")
	void twoConcurrentReadersRepeated() throws Exception {
		twoReadersScenario();
	}

	@Test
	void twoConcurrentReadersWithAConcurrentWriterServeEveryEventOnce() throws Exception {
		insertEvents(EVENTS);
		int written = 40;
		CyclicBarrier barrier = new CyclicBarrier(3);
		AtomicBoolean writing = new AtomicBoolean(true);
		Future<Void> writer = this.threads.submit(() -> {
			barrier.await(10, TimeUnit.SECONDS);
			for (int i = 0; i < written; i++) {
				insertEvents(1);
			}
			writing.set(false);
			return null;
		});
		Future<List<ChangePage>> first = this.threads.submit(() -> readWhile(barrier, writing));
		Future<List<ChangePage>> second = this.threads.submit(() -> readWhile(barrier, writing));
		writer.get(30, TimeUnit.SECONDS);
		List<ChangePage> pages = new ArrayList<>(first.get(30, TimeUnit.SECONDS));
		pages.addAll(second.get(30, TimeUnit.SECONDS));

		pages.forEach(ConcurrentFeedReadersIT::assertContiguousFromAfter);
		int total = EVENTS + written;
		Set<Long> served = servedSeqs(pages);
		served.addAll(readToTheEnd(0));
		assertThat(served).as("tous les événements finalement servis").containsExactlyElementsOf(range(total));
		assertEachEventHasExactlyOneSeq(total);
	}

	// --- Scénario principal --------------------------------------------------------------------------------

	private void twoReadersScenario() throws Exception {
		insertEvents(EVENTS);
		CyclicBarrier barrier = new CyclicBarrier(2);
		Future<ChangePage> first = this.threads.submit(() -> {
			barrier.await(10, TimeUnit.SECONDS);
			return this.feed.read(LINK, 0, 500, ChangeFilter.ALL);
		});
		Future<ChangePage> second = this.threads.submit(() -> {
			barrier.await(10, TimeUnit.SECONDS);
			return this.feed.read(LINK, 0, 500, ChangeFilter.ALL);
		});
		ChangePage a = first.get(20, TimeUnit.SECONDS);
		ChangePage b = second.get(20, TimeUnit.SECONDS);

		long lastSeq = this.jdbc.queryForObject("select last_seq from integration_feed_state", Long.class);
		for (ChangePage page : List.of(a, b)) {
			assertContiguousFromAfter(page, 0);
			assertThat(page.lastSeq()).as("lastSeq ≤ last_seq").isLessThanOrEqualTo(lastSeq);
		}
		assertEachEventHasExactlyOneSeq(EVENTS);
		assertThat(lastSeq).isEqualTo(EVENTS);

		Set<Long> served = servedSeqs(List.of(a, b));
		served.addAll(readToTheEnd(Math.min(a.lastSeq(), b.lastSeq())));
		assertThat(served).as("aucune perte").containsExactlyElementsOf(range(EVENTS));
	}

	// --- Outils ------------------------------------------------------------------------------------------------

	/** Lit depuis 0, page après page, tant que l'écrivain écrit ; chaque page commence après la précédente. */
	private List<ChangePage> readWhile(CyclicBarrier barrier, AtomicBoolean writing) throws Exception {
		barrier.await(10, TimeUnit.SECONDS);
		List<ChangePage> pages = new ArrayList<>();
		long after = 0;
		do {
			ChangePage page = this.feed.read(LINK, after, 7, ChangeFilter.ALL);
			pages.add(page);
			assertContiguousFromAfter(page, after);
			after = page.lastSeq();
		}
		while (writing.get());
		return pages;
	}

	/** Lit depuis {@code after} jusqu'à {@code hasMore = false} ; renvoie les seq servis. */
	private Set<Long> readToTheEnd(long after) {
		Set<Long> served = new TreeSet<>();
		long cursor = after;
		ChangePage page;
		do {
			page = this.feed.read(LINK, cursor, 500, ChangeFilter.ALL);
			assertContiguousFromAfter(page, cursor);
			page.items().forEach(event -> served.add(event.seq()));
			cursor = page.lastSeq();
		}
		while (page.hasMore());
		return served;
	}

	/** Page sans filtre : seq strictement croissants et contigus de {@code after + 1} à {@code lastSeq}. */
	private static void assertContiguousFromAfter(ChangePage page, long after) {
		List<Long> seqs = page.items().stream().map(ChangeEvent::seq).toList();
		assertThat(seqs).as("seq contigus après %d", after)
			.containsExactlyElementsOf(LongStream.rangeClosed(after + 1, page.lastSeq()).boxed().toList());
	}

	/** Variante sans {@code after} connu : contiguïté à partir du premier seq de la page. */
	private static void assertContiguousFromAfter(ChangePage page) {
		if (!page.items().isEmpty()) {
			assertContiguousFromAfter(page, page.items().getFirst().seq() - 1);
		}
	}

	private void assertEachEventHasExactlyOneSeq(int total) {
		assertThat(this.jdbc.queryForObject("select count(*) from integration_outbox where seq is null", Integer.class))
			.as("événements sans seq").isZero();
		assertThat(this.jdbc.queryForList("select seq from integration_outbox order by seq", Long.class))
			.as("un seq par événement, sans doublon ni trou").containsExactlyElementsOf(range(total));
		assertThat(this.jdbc.queryForObject("select last_seq from integration_feed_state", Long.class))
			.isEqualTo((long) total);
	}

	private static Set<Long> servedSeqs(List<ChangePage> pages) {
		Set<Long> served = new TreeSet<>();
		pages.forEach(page -> page.items().forEach(event -> served.add(event.seq())));
		return served;
	}

	private static List<Long> range(int total) {
		return LongStream.rangeClosed(1, total).boxed().toList();
	}

	private void insertEvents(int count) {
		for (int i = 0; i < count; i++) {
			this.jdbc.update("""
					insert into integration_outbox (type, operation, object_id, object_version)
					values ('CATEGORY', 'UPSERT', ?, 1)""", UUID.randomUUID());
		}
	}

	private void resetFeed() {
		this.jdbc.update("delete from integration_outbox");
		this.jdbc.update("""
				insert into integration_feed_state (id) values (?::uuid)
				on conflict (id) do update set last_seq = 0, purged_through_seq = 0""", FEED_STATE_ID);
	}

}
