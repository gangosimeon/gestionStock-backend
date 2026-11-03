package com.geststock.integration.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
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
import com.geststock.integration.SnapshotService;
import com.geststock.integration.link.Link;
import com.geststock.integration.link.LinkAcknowledgements;
import com.geststock.integration.link.LinkStatus;
import com.geststock.integration.link.RevocationReason;
import com.geststock.support.WithPermissions;
import com.geststock.tenancy.TenantContext;
import com.geststock.tenancy.TenantRef;

import tools.jackson.databind.JsonNode;

/**
 * Événements orphelins puis nouvelle liaison (G20-D9 ; plan § 33.6, hypothèses H1 à H6), à travers la route, sur de
 * vraies modifications du catalogue : une liaison L0 publie, n'est jamais lue, puis est révoquée ; la maintenance
 * séquence alors les événements restés sans {@code seq} et les purge après 90 jours, sans nuire à une liaison L1 activée
 * ensuite, même pendant un passage.
 *
 * <ul>
 * <li>H1 : séquencement <b>avant</b> la lecture de {@code P} (sinon rien ne serait supprimé au premier passage).</li>
 * <li>H2 : {@code P} lu avant la liste des liaisons (le passage arrêté après cette lecture ne voit pas L1).</li>
 * <li>H3 : {@code last_seq} ne diminue jamais.</li>
 * <li>H4 : {@code asOfSeq} de L1 = {@code last_seq} après le séquenceur.</li>
 * <li>H5 : drapeau de publication levé à l'activation de L1 (abaissé à la révocation de L0).</li>
 * <li>H6 : séquencement par la maintenance seulement sans liaison active ou suspendue.</li>
 * </ul>
 */
@WithPermissions(all = true)
class OrphanEventsAndNewLinkIT extends DeleteModeMaintenanceSupport {

	private static final String PREFIX = "ITG20B-ORPH-";

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

	private final List<Long> lastSeqs = new ArrayList<>();

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
	void anOrphanFeedIsSequencedThenPurgedWithoutHarmToANewLink() throws Exception {
		// 1. L0 publie quatre catégories, jamais lues (aucun seq) ; puis L0 est révoquée : publication arrêtée.
		Link l0 = activeLink(this.client, this.company, "shop-g20b-L0");
		assertThat(publicationEnabled()).isTrue();
		createCategories(4);
		antedate();
		this.registry.revoke(l0.id(), RevocationReason.SHOP_REQUEST);
		// Comme LinkAudit après une révocation (G13) : drapeau resynchronisé, abaissé sans liaison active ou suspendue.
		TenantContext.runAs(this.company, () -> this.publicationFlag.set(false));
		assertThat(publicationEnabled()).as("H5 : drapeau abaissé avec la dernière liaison").isFalse();
		assertThat(unsequenced()).isEqualTo(4);
		recordLastSeq();

		// 2. Premier passage, sans liaison : séquencement (H6) puis purge des 90 jours, plus grand seq conservé.
		JsonNode first = pass();
		assertThat(unsequenced()).isZero();
		assertThat(legacyLastSeq()).isEqualTo(4);
		assertThat(first.get("purge").get("deleted").asLong()).as("H1 : P lu après le séquencement").isEqualTo(3);
		assertThat(legacySeqs()).containsExactly(4L);
		recordLastSeq();

		// 3. Second passage, arrêté juste après sa lecture de la liste des liaisons pour la purge (2ᵉ lecture : la
		// première est le contrôle G20-D9). Pendant l'arrêt : activation réelle de L1, instantané, nouvelle catégorie.
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
		Link l1;
		long asOfSeq;
		UUID created;
		JsonNode second;
		try {
			Future<JsonNode> running = thread.submit(this::pass);
			assertThat(linksRead.await(30, TimeUnit.SECONDS)).isTrue();
			l1 = activeLink(this.client, this.company, "shop-g20b-L1");
			assertThat(this.registry.find(l1.id()).orElseThrow().status()).isEqualTo(LinkStatus.ACTIVE);
			assertThat(publicationEnabled()).as("H5 : drapeau levé avec L1").isTrue();
			asOfSeq = TenantContext.callAs(this.company, () -> this.snapshots.read(new LinkRef(l1.id()), null, 500).asOfSeq());
			assertThat(asOfSeq).as("H4 : asOfSeq = last_seq").isEqualTo(legacyLastSeq());
			created = createCategories(1).get(0);
			// La lecture du flux par L1 séquence le nouvel événement (seq 5).
			assertThat(feedObjects(l1, asOfSeq)).containsExactly(created);
			resume.countDown();
			second = running.get(60, TimeUnit.SECONDS);
		}
		finally {
			resume.countDown();
			thread.shutdownNow();
		}
		recordLastSeq();

		// Rien de dû à L1 n'est supprimé : seq 4 (≤ asOfSeq) couvert par son instantané, seq 5 conservé.
		assertThat(asOfSeq).isEqualTo(4);
		assertThat(second.get("purge").get("deleted").asLong()).isEqualTo(1);
		assertThat(legacySeqs()).as("aucun seq au-dessus de asOfSeq supprimé").containsExactly(5L);
		assertThat(legacyPurgedThrough()).as("repère ≤ asOfSeq").isLessThanOrEqualTo(asOfSeq);
		assertThat(feedObjects(l1, asOfSeq)).as("lecture depuis asOfSeq sans 410").containsExactly(created);

		// 4. Passage suivant : L1 existe, la maintenance ne séquence plus (H6) ; L1 sans acquittement bloque la purge.
		clearInvocations(this.feed);
		JsonNode blocked = pass();
		verify(this.feed, never()).sequence(any());
		assertThat(blocked.get("purge").get("blocked").asInt()).isEqualTo(1);
		assertThat(legacySeqs()).containsExactly(5L);
		recordLastSeq();

		// 5. Deux catégories de plus, lues par L1, qui acquitte 6 ; tout est antidaté : la purge suit l'acquittement.
		createCategories(2);
		assertThat(feedObjects(l1, 5)).hasSize(2);
		TenantContext.callAs(this.company, () -> this.acknowledgements.acknowledge(l1.id(), 6));
		antedate();
		JsonNode followed = pass();
		assertThat(followed.get("purge").get("deleted").asLong()).isEqualTo(2);
		assertThat(legacySeqs()).as("purge jusqu'à l'acquittement de L1, pas au-delà").containsExactly(7L);
		assertThat(legacyPurgedThrough()).isEqualTo(6);
		recordLastSeq();

		assertThat(this.lastSeqs).as("H3 : last_seq ne diminue jamais").isSorted();
	}

	// --- Outils --------------------------------------------------------------------------------------------------

	private List<UUID> createCategories(int count) {
		List<UUID> ids = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			ids.add(this.categories.create(new CategoryRequest(PREFIX + UUID.randomUUID().toString().substring(0, 8), null)).id());
		}
		return ids;
	}

	private void antedate() {
		this.jdbc.update("update integration_outbox set created_at = now() - interval '100 days'");
	}

	private List<UUID> feedObjects(Link link, long after) {
		return TenantContext.callAs(this.company, () -> this.changes.read(new LinkRef(link.id()), after, 500, ChangeFilter.ALL)
			.items().stream().map(ChangeEvent::objectId).toList());
	}

	private boolean publicationEnabled() {
		return Boolean.TRUE.equals(this.jdbc.queryForObject("select enabled from integration_publication", Boolean.class));
	}

	private int unsequenced() {
		return this.jdbc.queryForObject("select count(*) from integration_outbox where seq is null", Integer.class);
	}

	private void recordLastSeq() {
		this.lastSeqs.add(legacyLastSeq());
	}

}
