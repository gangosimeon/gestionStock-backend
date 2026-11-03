package com.geststock.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;

import com.geststock.integration.link.Authorizer;
import com.geststock.integration.link.Link;
import com.geststock.integration.link.LinkAcknowledgements;
import com.geststock.integration.link.LinkRegistry;
import com.geststock.integration.link.RetainedLinks;
import com.geststock.platform.PlatformDb;
import com.geststock.support.PostgresTestConfiguration;
import com.geststock.tenancy.TenantContext;
import com.geststock.tenancy.TenantRef;

/**
 * Course entre l'activation d'une liaison et une purge (plan § 30.5, premier test de G17 ; v17-D11) : une liaison
 * activée pendant un passage ne perd aucun événement postérieur à son instantané.
 *
 * <p>Tout est réel : la purge ({@link OutboxPurge}, mode {@code DELETE}, base Testcontainers isolée), qui lit son
 * plafond {@code P = last_seq} ; la lecture de la liste des liaisons ({@link RetainedLinks}, espionné pour arrêter la
 * purge juste <b>après</b> cette lecture réelle) ; les acquittements ({@link LinkAcknowledgements}, table
 * {@code integration_link_ack}) ; l'activation ({@link LinkRegistry#activate}) ; la première page d'instantané
 * ({@link SnapshotService#read}) ; le séquenceur et la lecture du flux.
 *
 * <p>Ordre reproduit (contre-exemple du § 30.5) : la purge lit {@code P}, puis la liste des liaisons, sans L ; L est
 * activée et lit sa première page ({@code asOfSeq}) ; trois événements suivent ; l'autre liaison acquitte 8 ; la purge
 * lit alors les acquittements et supprime. Synchronisation par verrous à compte ({@link CountDownLatch}), sans délai.
 *
 * <p>Les événements sont antidatés de 100 jours : la règle des 90 jours rend la course improbable sans la rendre
 * impossible ; le test l'écarte pour observer la seule borne.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class PurgeActivationRaceIT {

	private static final String FEED_STATE_ID = "00000000-0000-0000-0000-000000000001";

	private static final String REDIRECT_URI = "https://www.jecreemaboutique.com/integrations/geststock/callback";

	private static final Set<String> SCOPES = Set.of("catalog:read");

	@Autowired
	private FeedSequencer sequencer;

	@Autowired
	private ChangeFeedService feed;

	@Autowired
	private SnapshotService snapshots;

	@Autowired
	private LinkRegistry registry;

	@Autowired
	private AckSource acknowledgements;

	@Autowired
	private LinkAcknowledgements store;

	/** Espion : arrête la purge juste après sa lecture réelle de la liste des liaisons. */
	@MockitoSpyBean
	private RetainedLinks retainedLinks;

	@Autowired
	private PlatformDb platform;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private JdbcTemplate jdbc;

	private TenantRef company;

	private UUID client;

	@BeforeEach
	void setUp() {
		this.jdbc = new JdbcTemplate(this.dataSource);
		this.company = new TenantRef(
				this.platform.jdbc().sql("select id from platform.company where code = 'test'").query(UUID.class).single(),
				"test");
		resetFeed();
		this.client = this.platform.jdbc().sql("""
				insert into platform.integration_client (client_id, name, secret_hash, allowed_scopes, status)
				values (:clientId, 'JeCréeMaBoutique (essai G17)', :hash, ARRAY['catalog:read']::varchar[], 'ACTIVE')
				returning id""")
			.param("clientId", "jcmb-g17-" + UUID.randomUUID().toString().substring(0, 8))
			.param("hash", sha256Hex(UUID.randomUUID().toString())).query(UUID.class).single();
		this.platform.jdbc().sql("insert into platform.integration_client_redirect_uri (client_id, redirect_uri) values (:c, :u)")
			.param("c", this.client).param("u", REDIRECT_URI).update();
	}

	@AfterEach
	void tearDown() {
		for (String sql : List.of("""
				delete from platform.integration_authorization_code where request_id in
				  (select id from platform.integration_authorization_request where client_id = :c)""",
				"delete from platform.integration_link where client_id = :c",
				"delete from platform.integration_authorization_request where client_id = :c",
				"delete from platform.integration_client where id = :c")) {
			this.platform.jdbc().sql(sql).param("c", this.client).update();
		}
		resetFeed();
	}

	@Test
	void aLinkActivatedDuringAPurgeKeepsEveryEventAfterItsSnapshot() throws Exception {
		// Préparation : une autre liaison O, active ; cinq événements séquencés anciens (seq 1 à 5).
		Link other = activeLink("shop-g17-autre");
		events(5);
		this.sequencer.sequence();
		assertThat(lastSeq()).isEqualTo(5L);

		CountDownLatch linksRead = new CountDownLatch(1);
		CountDownLatch resumePurge = new CountDownLatch(1);
		AtomicBoolean first = new AtomicBoolean(true);
		doAnswer(invocation -> {
			Object retained = invocation.callRealMethod();
			if (first.compareAndSet(true, false)) {
				linksRead.countDown();
				await(resumePurge);
			}
			return retained;
		}).when(this.retainedLinks).of(any());
		OutboxPurge purge = new OutboxPurge(this.dataSource, this.transactionManager, this.acknowledgements,
				new IntegrationPurgeProperties(PurgeMode.DELETE, Duration.ofDays(90), 1000, Duration.ofSeconds(2), 100,
						Duration.ofSeconds(30)));

		ExecutorService thread = Executors.newSingleThreadExecutor();
		try {
			// 1 et 2. La purge lit son plafond P, puis la liste des liaisons (sans L), et s'arrête juste après.
			Future<PurgeReport> running = thread.submit(() -> TenantContext.callAs(this.company, purge::run));
			assertThat(linksRead.await(20, TimeUnit.SECONDS)).as("la purge a lu la liste des liaisons").isTrue();

			// 3 et 4. L est réellement activée, puis lit réellement sa première page d'instantané.
			Link linkL = activeLink("shop-g17-L");
			long asOfSeq = this.snapshots.read(new LinkRef(linkL.id()), null, 500).asOfSeq();

			// 5. Trois événements suivent (seq 6 à 8) ; O acquitte réellement 8.
			events(3);
			this.sequencer.sequence();
			long otherAck = TenantContext.callAs(this.company, () -> this.store.acknowledge(other.id(), 8));

			// 6. La purge reprend : elle lit les acquittements, calcule sa borne et supprime.
			resumePurge.countDown();
			PurgeReport report = running.get(30, TimeUnit.SECONDS);

			// Contrôles préalables : le scénario s'est déroulé comme prévu.
			assertThat(asOfSeq).as("asOfSeq de L").isEqualTo(5L);
			assertThat(otherAck).as("acquittement de O, enregistré").isEqualTo(8L);
			assertThat(report.mode()).isEqualTo(PurgeMode.DELETE);
			assertThat(report.floor()).as("acquittements lus après celui de O").isEqualTo(AckFloor.acknowledged(8));

			// Invariants du § 30.5 (v17-D11), sur les données persistées.
			List<Long> remaining = this.jdbc.queryForList(
					"select seq from integration_outbox where seq is not null order by seq", Long.class);
			SoftAssertions invariants = new SoftAssertions();
			invariants.assertThat(report.ceilingSeq()).as("P = last_seq lu par la purge avant la liste").isEqualTo(5L);
			invariants.assertThat(report.eligibleThroughSeq()).as("borne ≤ P").isLessThanOrEqualTo(5L);
			invariants.assertThat(remaining).as("événements postérieurs à l'instantané de L conservés")
				.containsAll(List.of(6L, 7L, 8L));
			invariants.assertThat(purgedThrough()).as("repère ≤ asOfSeq de L").isLessThanOrEqualTo(asOfSeq);
			invariants.assertThat(feedFailureFrom(linkL, asOfSeq)).as("lecture de L depuis asOfSeq : pas de 410").isNull();
			invariants.assertAll();

			// Passage suivant : L est visible sans acquittement, la purge est bloquée (v17-D4).
			PurgeReport next = TenantContext.callAs(this.company, purge::run);
			assertThat(next.floor()).isEqualTo(AckFloor.blocked());
			assertThat(next.deletedCount()).isZero();
			assertThat(this.jdbc.queryForList("select seq from integration_outbox where seq is not null order by seq",
					Long.class)).isEqualTo(remaining);
		}
		finally {
			resumePurge.countDown();
			thread.shutdownNow();
		}
	}

	// --- Outils ------------------------------------------------------------------------------------------------

	/** Lecture réelle du flux de L depuis {@code after} ; l'exception levée, ou {@code null}. */
	private Class<? extends Throwable> feedFailureFrom(Link link, long after) {
		try {
			this.feed.read(new LinkRef(link.id()), after, null, ChangeFilter.ALL);
			return null;
		}
		catch (RuntimeException ex) {
			return ex.getClass();
		}
	}

	/** Liaison réellement créée puis activée par {@link LinkRegistry}, pour l'entreprise de test historique. */
	private Link activeLink(String shop) {
		UUID request = this.platform.jdbc().sql("""
				insert into platform.integration_authorization_request (request_uri_hash, client_id, external_shop_id, shop_name,
				  redirect_uri, code_challenge, scopes, state, expires_at, decision, used_at)
				values (:hash, :client, :shop, :name, :redirect, 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM',
				  ARRAY['catalog:read']::varchar[], repeat('s', 43), now() + interval '10 minutes', 'APPROVED', now())
				returning id""")
			.param("hash", sha256Hex(UUID.randomUUID().toString())).param("client", this.client).param("shop", shop)
			.param("name", "Boutique " + shop).param("redirect", REDIRECT_URI).query(UUID.class).single();
		Link pending = this.registry.createPending(request, this.company.companyId(), new Authorizer(UUID.randomUUID(), "admin"),
				SCOPES);
		return this.registry.activate(pending.id()).link();
	}

	/** {@code count} événements non séquencés, écrits il y a 100 jours (au-delà de la rétention de 90 jours). */
	private void events(int count) {
		for (int i = 0; i < count; i++) {
			this.jdbc.update("""
					insert into integration_outbox (type, operation, object_id, object_version, created_at)
					values ('CATEGORY', 'UPSERT', ?, 1, now() - interval '100 days')""", UUID.randomUUID());
		}
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

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(20, TimeUnit.SECONDS)) {
				throw new IllegalStateException("La purge n'a pas été relâchée par le test.");
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	private static String sha256Hex(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (java.security.NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
