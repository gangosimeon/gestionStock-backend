package com.geststock.integration.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.geststock.category.CategoryService;
import com.geststock.category.dto.CategoryRequest;
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
 * Publication seulement avec une liaison active ou suspendue (lot 2, étape G13 ; v12-D4, v14-D11) : drapeau dans la
 * base de l'entreprise, lu par chaque transaction qui publie avec un verrou partagé, passé à vrai (verrou exclusif)
 * avant l'activation de la liaison, resynchronisé après chaque révocation. L'interrupteur global reste obligatoire.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class PublicationFlagIT {

	private static final String FEED_STATE_ID = "00000000-0000-0000-0000-000000000001";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private PlatformDb platform;

	/** Espion : activation en échec après le passage du drapeau à vrai. */
	@MockitoSpyBean
	private LinkRegistry registry;

	/** Espion : synchronisation en échec après une révocation. */
	@MockitoSpyBean
	private LinkPublication publication;

	@Autowired
	private IntegrationTokenService linkTokens;

	@Autowired
	private StockService stock;

	@Autowired
	private CategoryService categories;

	@Autowired
	private SnapshotService snapshots;

	@Autowired
	private ChangeFeedService feed;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private JsonMapper json;

	private TestLinks links;

	private TenantRef company;

	private StockFixtures fixtures;

	private UUID actor;

	private UUID store;

	private String clientSecret;

	private TestLinks.Client client;

	@BeforeEach
	void setUp() {
		this.links = new TestLinks(this.platform, this.registry);
		this.company = new TenantRef(this.links.legacyCompany(), "test");
		this.fixtures = new StockFixtures(this.jdbc, this.transactions);
		this.actor = this.fixtures.user("g13");
		this.store = this.fixtures.warehouse("g13");
		this.clientSecret = "secret-g13-" + UUID.randomUUID();
		this.client = this.links.client(this.clientSecret, null, Set.of("catalog:read"));
		setFlag(false);
		resetFeed();
	}

	@AfterEach
	void tearDown() {
		this.links.cleanUp();
		this.jdbc.update("delete from integration_link_warehouse where warehouse_id = ?", this.store);
		this.fixtures.purge();
		setFlag(false);
		resetFeed();
	}

	// --- Sans liaison, rien ; avec une liaison active ou suspendue, tout -------------------------------------

	@Test
	void withoutALinkNoChangeWritesAnEvent() {
		UUID product = this.fixtures.product("g13-sans", 1000);
		receive(product, 3);
		UUID category = createCategory();
		assertThat(outbox()).as("aucun événement sans liaison").isZero();
		assertThat(eventsOf(product)).isZero();
		assertThat(eventsOf(category)).isZero();
	}

	@Test
	void activationOpensPublicationAndASuspensionKeepsItOpen() throws Exception {
		UUID before = this.fixtures.product("g13-avant", 1000);
		receive(before, 1);
		assertThat(eventsOf(before)).isZero();

		Exchanged link = exchange("shop-g13-open");
		assertThat(flag()).as("drapeau posé à l'activation").isTrue();
		UUID after = this.fixtures.product("g13-apres", 1000);
		receive(after, 2);
		assertThat(eventsOf(after)).as("publication après l'activation").isEqualTo(1);

		this.registry.suspend(link.id(), SuspensionReason.MANUAL);
		receive(after, 1);
		assertThat(eventsOf(after)).as("publication pendant la suspension").isEqualTo(2);
	}

	@Test
	void revokingTheLastLinkClosesPublicationButAnotherActiveLinkKeepsItOpen() throws Exception {
		Exchanged first = exchange("shop-g13-first");
		Exchanged second = exchange("shop-g13-second");
		UUID product = this.fixtures.product("g13-revoke", 1000);

		revokeByShop(first);
		receive(product, 1);
		assertThat(flag()).isTrue();
		assertThat(eventsOf(product)).as("une autre liaison active : la publication continue").isEqualTo(1);

		revokeByShop(second);
		assertThat(flag()).as("dernière liaison révoquée").isFalse();
		receive(product, 1);
		assertThat(eventsOf(product)).as("la publication s'arrête").isEqualTo(1);
		assertThat(outbox()).as("les événements déjà écrits restent (purge : lot 3)").isPositive();
	}

	// --- La course : drapeau lu à faux, liaison activée, instantané, validation sans événement -----------------

	@Test
	void aSaleThatReadTheFlagAsFalseDelaysTheActivationAndTheShopRebuildsTheRealState() throws Exception {
		UUID product = this.fixtures.product("g13-course", 1000);
		receive(product, 5);
		Pending pending = pendingExchange("shop-g13-race");
		CountDownLatch flagReadAsFalse = new CountDownLatch(1);
		CountDownLatch releaseSale = new CountDownLatch(1);
		AtomicBoolean activatedDuringSale = new AtomicBoolean();
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			// Transaction métier : lit le drapeau (faux) en publiant, puis reste ouverte.
			Future<?> sale = pool.submit(() -> this.transactions.executeWithoutResult(status -> {
				StockFixtures.runAs(this.actor, () -> this.stock.applyMovement(StockMovementCommand.of(this.store, product,
						StockMovementType.LOSS, -2, StockSourceType.MANUAL, "Vente pendant l'activation")));
				flagReadAsFalse.countDown();
				try {
					releaseSale.await(10, TimeUnit.SECONDS);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}));
			if (!flagReadAsFalse.await(10, TimeUnit.SECONDS)) {
				sale.get(1, TimeUnit.SECONDS);
				throw new AssertionError("la vente n'a pas lu le drapeau");
			}
			Future<String> activation = pool.submit(() -> {
				String body = exchange(pending);
				return body;
			});
			// L'activation attend la fin de la transaction qui a lu le drapeau à faux.
			Thread.sleep(1500);
			activatedDuringSale.set(activation.isDone());
			releaseSale.countDown();
			sale.get(30, TimeUnit.SECONDS);
			activation.get(30, TimeUnit.SECONDS);
		}
		finally {
			releaseSale.countDown();
			pool.shutdownNow();
		}
		assertThat(activatedDuringSale).as("activation bloquée tant que la vente est ouverte").isFalse();

		// La boutique : instantané, puis rejeu du flux depuis asOfSeq ; puis un changement suivant.
		LinkRef link = new LinkRef(pending.linkId());
		long asOfSeq = snapshotAvailability(link, product);
		receive(product, 4);
		JsonNode last = replay(link, asOfSeq, product);
		assertThat(last.get("available").asInt()).as("état reconstruit = état de la base").isEqualTo(stockOf(product));
	}

	// --- Échecs entre les deux bases -------------------------------------------------------------------------

	@Test
	void aFailedActivationAfterTheFlagWasRaisedIsSynchronizedBackToFalse() throws Exception {
		Pending pending = pendingExchange("shop-g13-fail");
		doThrow(new IllegalStateException("activation en échec (test)")).when(this.registry).activate(any(UUID.class));

		this.mvc.perform(post("/api/integration/v1/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.header(HttpHeaders.AUTHORIZATION, basic()).param("grant_type", "authorization_code").param("code", pending.code())
			.param("redirect_uri", TestLinks.REDIRECT_URI).param("code_verifier", pending.verifier()))
			.andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(200));

		assertThat(flag()).as("drapeau remis à faux : aucune liaison active").isFalse();
	}

	@Test
	void aFailedSynchronizationAfterARevocationLogsAWarningWithoutUserError() throws Exception {
		Exchanged link = exchange("shop-g13-sync");
		doThrow(new IllegalStateException("base de l'entreprise indisponible (test)")).when(this.publication)
			.synchronize(any(UUID.class));
		Logger logger = (Logger) LoggerFactory.getLogger(LinkAudit.class);
		ListAppender<ILoggingEvent> warnings = new ListAppender<>();
		warnings.start();
		logger.addAppender(warnings);
		try {
			revokeByShop(link);
		}
		finally {
			logger.detachAppender(warnings);
		}
		assertThat(warnings.list).anyMatch(event -> event.getLevel() == Level.WARN
				&& event.getFormattedMessage().contains(this.company.companyId().toString()));
		assertThat(flag()).as("événements toujours publiés : sans danger, rattrapage au lot 3").isTrue();
	}

	@Test
	void theSynchronizationIsIdempotent() throws Exception {
		org.mockito.Mockito.reset(this.publication);
		assertThat(this.publication.synchronize(this.company.companyId())).isFalse();
		assertThat(this.publication.synchronize(this.company.companyId())).isFalse();
		assertThat(flag()).isFalse();

		Exchanged link = exchange("shop-g13-idem");
		setFlag(false);
		assertThat(this.publication.synchronize(this.company.companyId())).isTrue();
		assertThat(this.publication.synchronize(this.company.companyId())).isTrue();
		assertThat(flag()).isTrue();
		assertThat(link.id()).isNotNull();
	}

	// --- Outils ---------------------------------------------------------------------------------------------

	private record Pending(UUID linkId, String code, String verifier) {
	}

	private record Exchanged(UUID id, String secret) {
	}

	private Exchanged exchange(String shop) throws Exception {
		Pending pending = pendingExchange(shop);
		JsonNode body = this.json.readTree(exchange(pending));
		return new Exchanged(UUID.fromString(body.get("link").get("id").asString()), body.get("link_secret").asString());
	}

	/** Demande approuvée, liaison en attente (dépôt de test), code ; l'échange reste à faire. */
	private Pending pendingExchange(String shop) throws Exception {
		String verifier = "verificateur-g13-" + UUID.randomUUID() + "-" + UUID.randomUUID();
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
		String code = "code-g13-" + UUID.randomUUID();
		this.platform.jdbc().sql("""
				insert into platform.integration_authorization_code (code_hash, request_id, link_id, expires_at)
				values (:hash, :request, :link, now() + interval '5 minutes')""")
			.param("hash", LinkRegistry.sha256Hex(code)).param("request", request).param("link", link.id()).update();
		return new Pending(link.id(), code, verifier);
	}

	private String exchange(Pending pending) throws Exception {
		return this.mvc.perform(post("/api/integration/v1/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.header(HttpHeaders.AUTHORIZATION, basic()).param("grant_type", "authorization_code").param("code", pending.code())
			.param("redirect_uri", TestLinks.REDIRECT_URI).param("code_verifier", pending.verifier()))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	private void revokeByShop(Exchanged link) throws Exception {
		String token = this.linkTokens.issue(link.id(), this.client.clientId(), Set.of("catalog:read")).value();
		this.mvc.perform(post("/api/integration/v1/link/revoke").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk());
	}

	/** Disponibilité du produit dans l'instantané de la liaison ; renvoie asOfSeq. */
	private long snapshotAvailability(LinkRef link, UUID product) {
		String token = null;
		long asOfSeq = -1;
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

	/** Dernière valeur AVAILABILITY servie au produit après asOfSeq. */
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
		assertThat(last).as("événement du changement suivant").isNotNull();
		return last;
	}

	private void receive(UUID product, int quantity) {
		StockFixtures.runAs(this.actor, () -> this.stock.applyMovement(StockMovementCommand.of(this.store, product,
				StockMovementType.RECEIPT, quantity, StockSourceType.MANUAL, "Stock G13")));
	}

	private UUID createCategory() {
		UUID id = this.transactions.execute(status -> StockFixtures.runAs(this.actor,
				() -> this.categories.create(new CategoryRequest("Catégorie G13 " + UUID.randomUUID(), null)).id()));
		this.jdbc.update("delete from category where id = ?", id);
		return id;
	}

	private int stockOf(UUID product) {
		return this.jdbc.queryForObject("select coalesce(sum(quantity), 0) from stock_level where product_id = ? and warehouse_id = ?",
				Integer.class, product, this.store);
	}

	private int eventsOf(UUID object) {
		return this.jdbc.queryForObject("select count(*) from integration_outbox where object_id = ?", Integer.class, object);
	}

	private int outbox() {
		return this.jdbc.queryForObject("select count(*) from integration_outbox", Integer.class);
	}

	private boolean flag() {
		return Boolean.TRUE.equals(this.jdbc.queryForObject("select enabled from integration_publication", Boolean.class));
	}

	private void setFlag(boolean value) {
		this.jdbc.update("update integration_publication set enabled = ?", value);
	}

	private String basic() {
		return "Basic " + Base64.getEncoder()
			.encodeToString((this.client.clientId() + ":" + this.clientSecret).getBytes(StandardCharsets.UTF_8));
	}

	private void resetFeed() {
		this.jdbc.update("delete from integration_outbox");
		this.jdbc.update("""
				insert into integration_feed_state (id) values (?::uuid)
				on conflict (id) do update set last_seq = 0, purged_through_seq = 0""", FEED_STATE_ID);
	}

}
