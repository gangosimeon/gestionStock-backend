package com.geststock.integration.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.geststock.integration.ChangeEvent;
import com.geststock.integration.ChangeFeedService;
import com.geststock.integration.ChangeFilter;
import com.geststock.integration.ChangePage;
import com.geststock.integration.IntegrationEventType;
import com.geststock.integration.LinkRef;
import com.geststock.integration.SnapshotPage;
import com.geststock.integration.SnapshotService;
import com.geststock.platform.PlatformDb;
import com.geststock.stock.StockFixtures;
import com.geststock.stock.StockMovementCommand;
import com.geststock.stock.StockMovementType;
import com.geststock.stock.StockService;
import com.geststock.stock.StockSourceType;
import com.geststock.support.PostgresTestConfiguration;
import com.geststock.tenancy.TenantRef;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Passage du drapeau de publication équitable et borné (correction de G13) : verrou de table EXCLUSIVE (les ventes
 * qui démarrent pendant le passage attendent derrière lui, au lieu de le repousser sans fin), au plus
 * {@code geststock.integration.publication.lock-timeout} (1 s ici, 2 s par défaut).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "geststock.integration.publication.lock-timeout=1s")
@Import(PostgresTestConfiguration.class)
class PublicationFlagTimeoutIT {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private PlatformDb platform;

	@Autowired
	private LinkRegistry registry;

	@Autowired
	private IntegrationTokenService linkTokens;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private JsonMapper json;

	private TestLinks links;

	private TenantRef company;

	private String clientSecret;

	private TestLinks.Client client;

	private ExecutorService pool;

	@Autowired
	private StockService stock;

	@Autowired
	private SnapshotService snapshots;

	@Autowired
	private ChangeFeedService feed;

	private StockFixtures fixtures;

	private UUID actor;

	private UUID store;

	@BeforeEach
	void setUp() {
		this.links = new TestLinks(this.platform, this.registry);
		this.company = new TenantRef(this.links.legacyCompany(), "test");
		this.clientSecret = "secret-g13t-" + UUID.randomUUID();
		this.client = this.links.client(this.clientSecret, null, Set.of("catalog:read"));
		this.jdbc.update("update integration_publication set enabled = false");
		this.pool = Executors.newCachedThreadPool();
		this.fixtures = new StockFixtures(this.jdbc, this.transactions);
		this.actor = this.fixtures.user("g13t");
		this.store = this.fixtures.warehouse("g13t");
		this.jdbc.update("delete from integration_outbox");
		this.jdbc.update("""
				insert into integration_feed_state (id) values ('00000000-0000-0000-0000-000000000001'::uuid)
				on conflict (id) do update set last_seq = 0, purged_through_seq = 0""");
	}

	@AfterEach
	void tearDown() {
		this.pool.shutdownNow();
		this.links.cleanUp();
		this.jdbc.update("update integration_publication set enabled = false");
		this.jdbc.update("delete from integration_link_warehouse where warehouse_id = ?", this.store);
		this.fixtures.purge();
		this.jdbc.update("delete from integration_outbox");
	}

	@Test
	void aSaleHoldingTheSharedLockBeyondTheDelayGetsA503AndTheCodeStaysUsable() throws Exception {
		Pending pending = pendingExchange("shop-g13t-hold");
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Future<?> sale = this.pool.submit(() -> holdSharedLock(locked, release));
		assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
		try {
			long start = System.nanoTime();
			MockHttpServletResponse refused = this.pool.submit(() -> exchange(pending)).get(10, TimeUnit.SECONDS);
			long elapsed = (System.nanoTime() - start) / 1_000_000;
			assertThat(refused.getStatus()).isEqualTo(503);
			assertThat(this.json.readTree(refused.getContentAsString()).get("error").asString()).isEqualTo("temporarily_unavailable");
			assertThat(elapsed).as("réponse après le délai, sans attendre la fin de la vente").isBetween(900L, 5000L);
			assertThat(codeUsed(pending)).as("code non consommé").isFalse();
		}
		finally {
			release.countDown();
			sale.get(10, TimeUnit.SECONDS);
		}
		MockHttpServletResponse accepted = exchange(pending);
		assertThat(accepted.getStatus()).as("second échange, vente terminée").isEqualTo(200);
		assertThat(flag()).isTrue();
	}

	@Test
	void aTimeoutDuringTheSynchronizationAfterARevocationLogsAWarningWithoutUserError() throws Exception {
		JsonNode exchanged = this.json.readTree(exchange(pendingExchange("shop-g13t-sync")).getContentAsString());
		UUID link = UUID.fromString(exchanged.get("link").get("id").asString());
		assertThat(flag()).isTrue();
		Logger logger = (Logger) LoggerFactory.getLogger(LinkAudit.class);
		ListAppender<ILoggingEvent> warnings = new ListAppender<>();
		warnings.start();
		logger.addAppender(warnings);
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Future<?> sale = this.pool.submit(() -> holdSharedLock(locked, release));
		assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
		try {
			String token = this.linkTokens.issue(link, this.client.clientId(), Set.of("catalog:read")).value();
			MockHttpServletResponse revoked = this.pool.submit(() -> this.mvc.perform(post("/api/integration/v1/link/revoke")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn().getResponse()).get(10, TimeUnit.SECONDS);
			assertThat(revoked.getStatus()).as("aucune erreur pour l'utilisateur").isEqualTo(200);
		}
		finally {
			release.countDown();
			sale.get(10, TimeUnit.SECONDS);
			logger.detachAppender(warnings);
		}
		assertThat(warnings.list).anyMatch(event -> event.getLevel() == Level.WARN
				&& event.getFormattedMessage().contains(this.company.companyId().toString()));
		assertThat(flag()).as("drapeau non baissé : rattrapage au lot 3").isTrue();
	}

	/**
	 * Famine : trois fils de ventes qui se chevauchent sans interruption gardent toujours un verrou partagé. Avec la
	 * file équitable (LOCK TABLE … IN EXCLUSIVE MODE), les nouvelles ventes attendent derrière le passage du drapeau :
	 * l'échange réussit pendant le flux, en moins du délai ; l'état reconstruit par la boutique (instantané puis
	 * rejeu) est celui de la base.
	 */
	@Test
	void aContinuousStreamOfSalesNoLongerStarvesTheActivation() throws Exception {
		// Un produit par fil : des ventes du même produit se sérialiseraient sur sa ligne de stock avant de lire le
		// drapeau, et ne garderaient jamais deux verrous partagés à la fois.
		List<UUID> products = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			UUID product = this.fixtures.product("g13t-flux-" + i, 1000);
			receive(product, 1000);
			products.add(product);
		}
		Pending pending = pendingExchange("shop-g13t-starve");
		AtomicBoolean streaming = new AtomicBoolean(true);
		CountDownLatch first = new CountDownLatch(1);
		List<Future<?>> workers = new ArrayList<>();
		for (int worker = 0; worker < 3; worker++) {
			int offset = worker;
			UUID product = products.get(worker);
			workers.add(this.pool.submit(() -> {
				sleep(offset * 150L);
				while (streaming.get()) {
					// Vente : sortie de stock (publie, donc lit le drapeau avec le verrou partagé), transaction longue.
					this.transactions.executeWithoutResult(status -> {
						StockFixtures.runAs(this.actor, () -> this.stock.applyMovement(StockMovementCommand.of(this.store, product,
								StockMovementType.LOSS, -1, StockSourceType.MANUAL, "Vente du flux")));
						first.countDown();
						sleep(450);
					});
				}
			}));
		}
		assertThat(first.await(10, TimeUnit.SECONDS)).isTrue();
		MockHttpServletResponse accepted;
		long elapsed;
		try {
			sleep(600);
			long start = System.nanoTime();
			accepted = this.pool.submit(() -> exchange(pending)).get(10, TimeUnit.SECONDS);
			elapsed = (System.nanoTime() - start) / 1_000_000;
			sleep(1200);
		}
		finally {
			streaming.set(false);
			for (Future<?> worker : workers) {
				worker.get(10, TimeUnit.SECONDS);
			}
		}
		assertThat(accepted.getStatus()).as("échange réussi pendant le flux").isEqualTo(200);
		assertThat(elapsed).as("en moins du délai (1 s)").isLessThan(1000L);

		LinkRef link = new LinkRef(pending.linkId());
		for (UUID product : products) {
			long asOfSeq = snapshotAvailability(link, product);
			receive(product, 1);
			assertThat(replay(link, asOfSeq, product).get("available").asInt()).as("état reconstruit = base")
				.isEqualTo(stockOf(product));
		}
	}

	/**
	 * Latence des ventes pendant une activation : une vente ouverte retient le passage du drapeau ; les ventes qui
	 * démarrent pendant ce passage attendent derrière lui, au plus le délai.
	 */
	@Test
	void salesStartingDuringTheFlagChangeWaitAtMostTheDelay() throws Exception {
		UUID product = this.fixtures.product("g13t-latence", 1000);
		receive(product, 100);
		Pending pending = pendingExchange("shop-g13t-latency");
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Future<?> open = this.pool.submit(() -> holdSharedLock(locked, release));
		assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
		Future<MockHttpServletResponse> activation = this.pool.submit(() -> exchange(pending));
		sleep(200);
		List<Future<Long>> sales = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			sales.add(this.pool.submit(() -> {
				long start = System.nanoTime();
				this.transactions.executeWithoutResult(status -> StockFixtures.runAs(this.actor, () -> this.stock.applyMovement(
						StockMovementCommand.of(this.store, product, StockMovementType.LOSS, -1, StockSourceType.MANUAL,
								"Vente pendant l'activation"))));
				return (System.nanoTime() - start) / 1_000_000;
			}));
			sleep(50);
		}
		sleep(100);
		release.countDown();
		open.get(10, TimeUnit.SECONDS);
		assertThat(activation.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
		List<Long> latencies = new ArrayList<>();
		for (Future<Long> sale : sales) {
			latencies.add(sale.get(10, TimeUnit.SECONDS));
		}
		long max = latencies.stream().mapToLong(Long::longValue).max().orElseThrow();
		System.out.printf("G13 latence des ventes pendant le passage du drapeau (ms) : %s ; maximum %d ms%n", latencies, max);
		assertThat(max).as("attente bornée par le délai (1 s) et la marge d'exécution").isLessThan(1500L);
	}

	// --- Outils ---------------------------------------------------------------------------------------------

	private record Pending(UUID linkId, String code, String verifier) {
	}

	private void receive(UUID product, int quantity) {
		StockFixtures.runAs(this.actor, () -> this.stock.applyMovement(StockMovementCommand.of(this.store, product,
				StockMovementType.RECEIPT, quantity, StockSourceType.MANUAL, "Stock G13")));
	}

	private int stockOf(UUID product) {
		return this.jdbc.queryForObject("select coalesce(sum(quantity), 0) from stock_level where product_id = ? and warehouse_id = ?",
				Integer.class, product, this.store);
	}

	/** Disponibilité du produit dans l'instantané (égale à la base) ; renvoie asOfSeq. */
	private long snapshotAvailability(LinkRef link, UUID product) {
		String token = null;
		long asOfSeq;
		boolean seen = false;
		do {
			SnapshotPage page = this.snapshots.read(link, token, 500);
			asOfSeq = page.asOfSeq();
			for (JsonNode value : page.availability()) {
				if (value.get("productId").asString().equals(product.toString())) {
					assertThat(value.get("available").asInt()).as("instantané = base").isEqualTo(stockOf(product));
					seen = true;
				}
			}
			token = page.nextPageToken();
		}
		while (token != null);
		assertThat(seen).isTrue();
		return asOfSeq;
	}

	private JsonNode replay(LinkRef link, long after, UUID product) {
		JsonNode last = null;
		long cursor = after;
		while (true) {
			ChangePage page = this.feed.read(link, cursor, 500, ChangeFilter.ALL);
			for (ChangeEvent event : page.items()) {
				if (event.type() == IntegrationEventType.AVAILABILITY && event.objectId().equals(product)) {
					last = event.data();
				}
			}
			cursor = page.lastSeq();
			if (!page.hasMore()) {
				break;
			}
		}
		assertThat(last).isNotNull();
		return last;
	}

	/** Transaction qui lit le drapeau avec le verrou partagé (comme une vente qui publie) et reste ouverte. */
	private void holdSharedLock(CountDownLatch locked, CountDownLatch release) {
		this.transactions.executeWithoutResult(status -> {
			this.jdbc.queryForObject("select enabled from integration_publication for share", Boolean.class);
			locked.countDown();
			try {
				release.await(30, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		});
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private Pending pendingExchange(String shop) throws Exception {
		String verifier = "verificateur-g13t-" + UUID.randomUUID() + "-" + UUID.randomUUID();
		String challenge = Base64.getUrlEncoder().withoutPadding()
			.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
		UUID request = this.platform.jdbc().sql("""
				insert into platform.integration_authorization_request (request_uri_hash, client_id, external_shop_id, shop_name,
				  redirect_uri, code_challenge, scopes, state, expires_at, decision, used_at)
				values (:hash, :client, :shop, :name, :redirect, :challenge, ARRAY['catalog:read']::varchar[], repeat('s', 43),
				  now() + interval '10 minutes', 'APPROVED', now())
				returning id""")
			.param("hash", LinkRegistry.sha256Hex(UUID.randomUUID().toString())).param("client", this.client.id())
			.param("shop", shop).param("name", "Boutique " + shop).param("redirect", TestLinks.REDIRECT_URI)
			.param("challenge", challenge).query(UUID.class).single();
		Link link = this.registry.createPending(request, this.company.companyId(), new Authorizer(UUID.randomUUID(), "admin"),
				Set.of("catalog:read"));
		this.jdbc.update("insert into integration_link_warehouse (link_id, warehouse_id, added_by) values (?, ?, ?)", link.id(),
				this.store, this.actor);
		String code = "code-g13t-" + UUID.randomUUID();
		this.platform.jdbc().sql("""
				insert into platform.integration_authorization_code (code_hash, request_id, link_id, expires_at)
				values (:hash, :request, :link, now() + interval '5 minutes')""")
			.param("hash", LinkRegistry.sha256Hex(code)).param("request", request).param("link", link.id()).update();
		return new Pending(link.id(), code, verifier);
	}

	private MockHttpServletResponse exchange(Pending pending) throws Exception {
		return this.mvc.perform(post("/api/integration/v1/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.header(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder()
				.encodeToString((this.client.clientId() + ":" + this.clientSecret).getBytes(StandardCharsets.UTF_8)))
			.param("grant_type", "authorization_code").param("code", pending.code())
			.param("redirect_uri", TestLinks.REDIRECT_URI).param("code_verifier", pending.verifier()))
			.andReturn().getResponse();
	}

	private boolean codeUsed(Pending pending) {
		return this.platform.jdbc().sql("select used_at is not null from platform.integration_authorization_code where code_hash = :hash")
			.param("hash", LinkRegistry.sha256Hex(pending.code())).query(Boolean.class).single();
	}

	private boolean flag() {
		return Boolean.TRUE.equals(this.jdbc.queryForObject("select enabled from integration_publication", Boolean.class));
	}

}
