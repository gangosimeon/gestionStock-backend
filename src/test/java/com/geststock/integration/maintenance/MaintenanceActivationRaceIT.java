package com.geststock.integration.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import com.geststock.category.CategoryService;
import com.geststock.category.dto.CategoryRequest;
import com.geststock.integration.ChangeEvent;
import com.geststock.integration.ChangeFeedService;
import com.geststock.integration.ChangeFilter;
import com.geststock.integration.LinkRef;
import com.geststock.integration.PassControl;
import com.geststock.integration.SnapshotPage;
import com.geststock.integration.SnapshotService;
import com.geststock.integration.link.Link;
import com.geststock.integration.link.LinkAcknowledgements;
import com.geststock.support.WithPermissions;
import com.geststock.tenancy.TenantContext;
import com.geststock.tenancy.TenantRef;

import tools.jackson.databind.JsonNode;

/**
 * Activation d'une liaison pendant un passage de maintenance, à travers la route (lot 3, G20b ; § 30.5, v17-D11 ; G17
 * {@code PurgeActivationRaceIT} rejoué par l'orchestration). Événements produits par de vraies modifications du
 * catalogue ({@link CategoryService}), donc présents dans l'instantané.
 *
 * <p>Ordre reproduit : le passage lit {@code P}, puis la liste des liaisons (sans L) et s'arrête ; L est activée et lit
 * son instantané <b>complet</b> ({@code asOfSeq}) ; trois catégories suivent ; l'autre liaison O acquitte ; le passage
 * reprend et supprime. Vérifié précisément : ce que l'instantané de L contient (les catégories des événements jusqu'à
 * {@code asOfSeq}) ; ce que le flux de L lui livre après {@code asOfSeq} (les trois catégories suivantes, sans
 * {@code 410}) ; ce que les acquittements protègent (passage suivant bloqué tant que L n'a pas acquitté, puis purge
 * jusqu'au plus petit acquittement, plus grand {@code seq} conservé).
 */
@WithPermissions(all = true)
class MaintenanceActivationRaceIT extends DeleteModeMaintenanceSupport {

	private static final String PREFIX = "ITG20B-";

	@Autowired
	private CategoryService categories;

	@Autowired
	private SnapshotService snapshots;

	@Autowired
	private ChangeFeedService changes;

	@Autowired
	private LinkAcknowledgements acknowledgements;

	private TenantRef company;

	private UUID client;

	@BeforeEach
	void setUp() {
		this.company = legacy();
		resetLegacyFeed();
		this.client = createClient();
		forgetMaintenanceState(List.of(this.company.companyId()));
		doReturn(List.of(this.company)).when(this.tenants).active();
	}

	@AfterEach
	void tearDown() {
		deleteClient(this.client);
		this.jdbc.update("delete from category where name like ?", PREFIX + "%");
		this.jdbc.update("update integration_publication set enabled = false");
		resetLegacyFeed();
	}

	@Test
	void aLinkActivatedDuringAPassSeesInItsSnapshotOrItsFeedEveryEventThePassDeletesOrKeeps() throws Exception {
		Link other = activeLink(this.client, this.company, "shop-g20b-O");
		List<UUID> before = createCategories(5);
		sequenceAndAntedate();
		assertThat(legacySeqs()).containsExactly(1L, 2L, 3L, 4L, 5L);

		// Le passage s'arrête juste après sa lecture réelle de la liste des liaisons pour la purge (2ᵉ lecture : la
		// première est le contrôle G20-D9 du service, qui ne séquence pas puisque O existe).
		CountDownLatch linksRead = new CountDownLatch(1);
		CountDownLatch resume = new CountDownLatch(1);
		AtomicInteger reads = new AtomicInteger();
		AtomicBoolean armed = new AtomicBoolean(true);
		doAnswer(invocation -> {
			Object retained = invocation.callRealMethod();
			if (armed.get() && reads.incrementAndGet() == 2) {
				armed.set(false);
				linksRead.countDown();
				assertThat(resume.await(30, TimeUnit.SECONDS)).isTrue();
			}
			return retained;
		}).when(this.retainedLinks).of(any());

		ExecutorService thread = Executors.newSingleThreadExecutor();
		try {
			Future<JsonNode> running = thread.submit(this::pass);
			assertThat(linksRead.await(30, TimeUnit.SECONDS)).as("le passage a lu la liste des liaisons").isTrue();

			// L est réellement activée, puis lit son instantané complet.
			Link linkL = activeLink(this.client, this.company, "shop-g20b-L");
			Snapshot snapshotOfL = snapshot(linkL);
			// Trois catégories suivent (seq 6 à 8) ; O acquitte 8.
			List<UUID> after = createCategories(3);
			sequenceAndAntedate();
			TenantContext.callAs(this.company, () -> this.acknowledgements.acknowledge(other.id(), 8));

			resume.countDown();
			JsonNode report = running.get(60, TimeUnit.SECONDS);

			// Scénario déroulé comme prévu.
			assertThat(snapshotOfL.asOfSeq()).as("asOfSeq de L = P du passage").isEqualTo(5);
			assertThat(report.get("status").asString()).isEqualTo("COMPLETED");

			// Instantané de L : il contient l'état des cinq catégories des événements 1 à 5, et aucune des suivantes.
			assertThat(snapshotOfL.categoryIds()).containsAll(before).doesNotContainAnyElementsOf(after);
			// Le passage n'a supprimé que ce que couvre l'instantané de L : seq ≤ asOfSeq (borne ≤ P = 5).
			assertThat(report.get("purge").get("deleted").asLong()).isEqualTo(5);
			assertThat(legacySeqs()).as("événements postérieurs à l'instantané conservés").containsExactly(6L, 7L, 8L);
			assertThat(legacyPurgedThrough()).as("repère ≤ asOfSeq de L").isLessThanOrEqualTo(snapshotOfL.asOfSeq());
			// Flux de L après asOfSeq : exactement les trois catégories suivantes, sans 410.
			assertThat(feedObjects(linkL, snapshotOfL.asOfSeq())).containsExactlyElementsOf(after);

			// Acquittements : L n'a rien acquitté, le passage suivant est bloqué (v17-D4).
			JsonNode blocked = pass();
			assertThat(blocked.get("purge").get("blocked").asInt()).isEqualTo(1);
			assertThat(blocked.get("purge").get("deleted").asLong()).isZero();
			assertThat(legacySeqs()).containsExactly(6L, 7L, 8L);

			// L acquitte 8 : la purge suit le plus petit acquittement (8), le plus grand seq est conservé.
			TenantContext.callAs(this.company, () -> this.acknowledgements.acknowledge(linkL.id(), 8));
			JsonNode afterAck = pass();
			assertThat(afterAck.get("purge").get("deleted").asLong()).isEqualTo(2);
			assertThat(legacySeqs()).containsExactly(8L);
			assertThat(feedObjects(linkL, 8)).as("rien de dû à L n'est perdu").isEmpty();
			// L'état des huit catégories reste dans l'instantané : seul l'historique du flux a été purgé.
			Set<UUID> all = new HashSet<>(before);
			all.addAll(after);
			assertThat(snapshot(linkL).categoryIds()).containsAll(all);
		}
		finally {
			resume.countDown();
			thread.shutdownNow();
		}
	}

	// --- Outils --------------------------------------------------------------------------------------------------

	private List<UUID> createCategories(int count) {
		List<UUID> ids = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			ids.add(this.categories.create(new CategoryRequest(PREFIX + UUID.randomUUID().toString().substring(0, 8), null)).id());
		}
		return ids;
	}

	/** Séquencement réel (façade, sans limite), puis événements antidatés de 100 jours (au-delà de la rétention). */
	private void sequenceAndAntedate() {
		TenantContext.runAs(this.company, () -> this.feed.sequence(PassControl.UNLIMITED));
		this.jdbc.update("update integration_outbox set created_at = now() - interval '100 days'");
	}

	/** Instantané complet de la liaison : asOfSeq et identifiants des catégories de toutes ses pages. */
	private Snapshot snapshot(Link link) {
		return TenantContext.callAs(this.company, () -> {
			Set<UUID> ids = new HashSet<>();
			String token = null;
			long asOfSeq;
			do {
				SnapshotPage page = this.snapshots.read(new LinkRef(link.id()), token, 500);
				asOfSeq = page.asOfSeq();
				page.categories().forEach(category -> ids.add(UUID.fromString(category.get("id").asString())));
				token = page.nextPageToken();
			}
			while (token != null);
			return new Snapshot(asOfSeq, ids);
		});
	}

	/** Objets des événements que le flux livre à la liaison après {@code after} (une exception y fait échouer le test). */
	private List<UUID> feedObjects(Link link, long after) {
		return TenantContext.callAs(this.company, () -> this.changes.read(new LinkRef(link.id()), after, 500, ChangeFilter.ALL)
			.items().stream().map(ChangeEvent::objectId).toList());
	}

	private record Snapshot(long asOfSeq, Set<UUID> categoryIds) {
	}

}
