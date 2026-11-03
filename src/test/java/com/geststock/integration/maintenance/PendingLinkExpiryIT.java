package com.geststock.integration.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpHeaders;

import com.geststock.integration.link.Authorizer;
import com.geststock.integration.link.Link;
import com.geststock.integration.link.SuspensionReason;
import com.geststock.tenancy.TenantRef;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;

/**
 * Révocation globale des liaisons en attente expirées par la maintenance (lot 3, G20d ; décisions E1 à E4 ; plan
 * § 33.8), à travers la route : même règle que l'expiration par boutique (code expiré, ou sans code au-delà de sa durée
 * de vie), même trace d'audit (auteur système), au plus 2 liaisons par passage dans ce contexte de test, liaisons
 * verrouillées par un échange en cours laissées au passage suivant ; aucun effet sur la publication ni sur le flux ;
 * aucun nettoyage après la perte du bail ; un échec isolé, signalé par l'alerte {@code CLEANUP_ERROR}.
 */
class PendingLinkExpiryIT extends DeleteModeMaintenanceSupport {

	private TenantRef company;

	private UUID client;

	private ListAppender<ILoggingEvent> logs;

	@BeforeEach
	void setUp() {
		this.company = legacy();
		resetLegacyFeed();
		this.client = createClient();
		forgetMaintenanceState(List.of(this.company.companyId()));
		doReturn(List.of(this.company)).when(this.tenants).active();
		// Liaisons en attente expirées laissées par d'autres classes de test : révoquées d'abord, pour des comptes exacts.
		while (this.registry.expireStalePending(1000) > 0) {
			// suite
		}
		clearInvocations(this.registry);
		this.logs = new ListAppender<>();
		this.logs.start();
		root().addAppender(this.logs);
	}

	@AfterEach
	void tearDown() {
		root().detachAppender(this.logs);
		deleteClient(this.client);
		this.jdbc.update("update integration_publication set enabled = false");
		resetLegacyFeed();
	}

	// --- Règle et trace (E1) ----------------------------------------------------------------------------------------

	@Test
	void expiredPendingLinksAreRevokedOnceEachWithOneSystemAuditEntry() throws Exception {
		Link expiredCode = pendingWithCode("shop-g20d-code-expire", "-1 minute");
		Link oldWithoutCode = pending("shop-g20d-sans-code-ancienne");
		antedateCreation(oldWithoutCode, "10 minutes");
		Link validCode = pendingWithCode("shop-g20d-code-valide", "4 minutes");
		Link youngWithoutCode = pending("shop-g20d-sans-code-recente");
		Link active = activeLink(this.client, this.company, "shop-g20d-active");
		Link suspended = activeLink(this.client, this.company, "shop-g20d-suspendue");
		this.registry.suspend(suspended.id(), SuspensionReason.MANUAL);

		JsonNode first = pass();
		assertThat(first.get("cleanup").get("expiredPendingLinks").asInt()).isEqualTo(2);
		assertThat(state(expiredCode)).isEqualTo("REVOKED/EXPIRED");
		assertThat(state(oldWithoutCode)).isEqualTo("REVOKED/EXPIRED");
		assertThat(state(validCode)).isEqualTo("PENDING/null");
		assertThat(state(youngWithoutCode)).isEqualTo("PENDING/null");
		assertThat(state(active)).isEqualTo("ACTIVE/null");
		assertThat(state(suspended)).isEqualTo("SUSPENDED/null");
		for (Link revoked : List.of(expiredCode, oldWithoutCode)) {
			assertThat(systemRevocations(revoked)).as("une trace, auteur système").isEqualTo(1);
			assertThat(companyAuditCopies(revoked)).as("copie dans le journal de l'entreprise").isEqualTo(1);
		}

		JsonNode second = pass();
		assertThat(second.get("cleanup").get("expiredPendingLinks").asInt()).as("idempotent").isZero();
		assertThat(systemRevocations(expiredCode)).isEqualTo(1);
		assertThat(systemRevocations(oldWithoutCode)).isEqualTo(1);
		assertThat(second.toString()).as("aucun identifiant dans le rapport")
			.doesNotContain(expiredCode.id().toString()).doesNotContain(oldWithoutCode.id().toString())
			.doesNotContain("shop-g20d");
	}

	// --- Volume borné, sans attente (E2) -----------------------------------------------------------------------------

	@Test
	void atMostTheLimitIsRevokedPerPassAndTheRestOnTheNextPass() throws Exception {
		List<Link> expired = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			expired.add(pendingWithCode("shop-g20d-limite-" + i, "-" + (3 - i) + " minutes"));
		}
		assertThat(pass().get("cleanup").get("expiredPendingLinks").asInt()).isEqualTo(2);
		assertThat(state(expired.get(0))).as("les plus anciennes d'abord").isEqualTo("REVOKED/EXPIRED");
		assertThat(state(expired.get(1))).isEqualTo("REVOKED/EXPIRED");
		assertThat(state(expired.get(2))).isEqualTo("PENDING/null");
		assertThat(pass().get("cleanup").get("expiredPendingLinks").asInt()).isEqualTo(1);
		assertThat(state(expired.get(2))).isEqualTo("REVOKED/EXPIRED");
	}

	@Test
	void aPendingLinkLockedByACodeExchangeIsSkippedThenRevokedOnTheNextPass() throws Exception {
		Link exchanging = pendingWithCode("shop-g20d-echange", "-1 minute");
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService thread = Executors.newSingleThreadExecutor();
		try {
			// Comme l'échange du code : ligne de la liaison verrouillée par une autre transaction plateforme.
			Future<?> exchange = thread.submit(() -> this.platform.inTransaction(status -> {
				this.platform.jdbc().sql("select id from platform.integration_link where id = :id for update")
					.param("id", exchanging.id()).query(UUID.class).single();
				locked.countDown();
				try {
					assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
				return null;
			}));
			assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
			JsonNode report = pass();
			assertThat(report.get("cleanup").get("expiredPendingLinks").asInt()).as("sautée, sans attendre").isZero();
			release.countDown();
			exchange.get(30, TimeUnit.SECONDS);
		}
		finally {
			release.countDown();
			thread.shutdownNow();
		}
		assertThat(state(exchanging)).isEqualTo("PENDING/null");
		assertThat(pass().get("cleanup").get("expiredPendingLinks").asInt()).isEqualTo(1);
		assertThat(state(exchanging)).isEqualTo("REVOKED/EXPIRED");
	}

	// --- Sans effet sur la publication ni sur le flux --------------------------------------------------------------

	@Test
	void theCleanupHasNoEffectOnPublicationNorOnTheFeed() throws Exception {
		Link active = activeLink(this.client, this.company, "shop-g20d-publiee");
		Link expired = pendingWithCode("shop-g20d-expiree-meme-entreprise", "-1 minute");
		this.jdbc.update("""
				insert into integration_outbox (type, operation, object_id, object_version, seq, created_at)
				select 'CATEGORY', 'UPSERT', gen_random_uuid(), 1, g, now() - interval '100 days' from generate_series(1, 3) g""");
		this.jdbc.update("update integration_feed_state set last_seq = 3");

		JsonNode report = pass();
		assertThat(report.get("cleanup").get("expiredPendingLinks").asInt()).isEqualTo(1);
		assertThat(state(expired)).isEqualTo("REVOKED/EXPIRED");
		assertThat(state(active)).isEqualTo("ACTIVE/null");
		assertThat(publicationEnabled()).as("drapeau de l'entreprise liée inchangé").isTrue();
		assertThat(this.jdbc.queryForObject("select count(*) from integration_outbox", Integer.class)).isEqualTo(3);
		assertThat(legacyLastSeq()).isEqualTo(3);
		assertThat(legacyPurgedThrough()).isZero();
	}

	// --- Place dans le passage (E3) et échec (E4) -------------------------------------------------------------------

	@Test
	void noCleanupAfterTheLeaseIsLost() throws Exception {
		Link expired = pendingWithCode("shop-g20d-bail-perdu", "-1 minute");
		this.jdbc.update("""
				insert into integration_outbox (type, operation, object_id, object_version, seq, created_at)
				select 'CATEGORY', 'UPSERT', gen_random_uuid(), 1, g, now() - interval '100 days' from generate_series(1, 3) g""");
		this.jdbc.update("update integration_feed_state set last_seq = 3");
		doReturn(false).when(this.lease).isHeld(any());

		JsonNode report = pass();
		assertThat(report.get("status").asString()).isEqualTo("FAILED");
		assertThat(report.get("cleanup").get("expiredPendingLinks").asInt()).isZero();
		assertThat(state(expired)).isEqualTo("PENDING/null");
		verify(this.registry, never()).expireStalePending(anyInt());
	}

	@Test
	void aCleanupFailureIsIsolatedRaisesAnAlertAndLeaksNoMessage() throws Exception {
		pendingWithCode("shop-g20d-echec", "-1 minute");
		String marker = "marqueur-nettoyage-" + UUID.randomUUID();
		doThrow(new IllegalStateException(marker)).when(this.registry).expireStalePending(anyInt());

		String body = this.mvc.perform(post(MaintenanceRouteIT.ROUTE)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + MaintenanceRouteIT.CURRENT))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		JsonNode report = this.json.readTree(body);
		assertThat(report.get("status").asString()).as("statut inchangé").isEqualTo("COMPLETED");
		assertThat(report.get("cleanup").get("expiredPendingLinks").asInt()).isZero();
		assertThat(report.get("alerts")).singleElement().satisfies(alert -> {
			assertThat(alert.get("type").asString()).isEqualTo("CLEANUP_ERROR");
			assertThat(alert.get("severity").asString()).isEqualTo("WARNING");
			assertThat(alert.get("count").asInt()).isEqualTo(1);
		});
		assertThat(body).doesNotContain(marker).doesNotContain("IllegalStateException");
		assertThat(List.copyOf(this.logs.list)).as("message de l'erreur absent des journaux")
			.allSatisfy(event -> assertThat(render(event)).doesNotContain(marker));
	}

	// --- Outils --------------------------------------------------------------------------------------------------

	private Link pending(String shop) {
		Link link = this.registry.createPending(approvedRequest(this.client, shop), this.company.companyId(),
				new Authorizer(UUID.randomUUID(), "admin"), Set.of("catalog:read"));
		return link;
	}

	/** Liaison en attente avec son code, qui expire dans {@code expiresIn} (négatif : déjà expiré). */
	private Link pendingWithCode(String shop, String expiresIn) {
		UUID request = approvedRequest(this.client, shop);
		Link link = this.registry.createPending(request, this.company.companyId(), new Authorizer(UUID.randomUUID(), "admin"),
				Set.of("catalog:read"));
		this.platform.jdbc().sql("""
				insert into platform.integration_authorization_code (code_hash, request_id, link_id, expires_at, created_at)
				values (:hash, :request, :link, now() + cast(:expiresIn as interval),
				  now() + cast(:expiresIn as interval) - interval '5 minutes')""")
			.param("hash", sha256Hex(UUID.randomUUID().toString())).param("request", request).param("link", link.id())
			.param("expiresIn", expiresIn).update();
		return link;
	}

	private void antedateCreation(Link link, String age) {
		this.platform.jdbc().sql("update platform.integration_link set created_at = now() - cast(:age as interval) where id = :id")
			.param("age", age).param("id", link.id()).update();
	}

	/** « STATUT/motif de révocation ». */
	private String state(Link link) {
		return this.platform.jdbc().sql("select status || '/' || coalesce(revocation_reason, 'null') from platform.integration_link where id = :id")
			.param("id", link.id()).query(String.class).single();
	}

	private int systemRevocations(Link link) {
		return this.platform.jdbc().sql("""
				select count(*) from platform.integration_audit_log
				where link_id = :id and action = 'LINK_REVOKED' and actor_type = 'SYSTEM' and actor_name = 'expiration'
				  and details ->> 'revocationReason' = 'EXPIRED'""").param("id", link.id()).query(Integer.class).single();
	}

	private int companyAuditCopies(Link link) {
		return this.jdbc.queryForObject("select count(*) from audit_log where action = 'LINK_REVOKED' and entity_id = ?",
				Integer.class, link.id().toString());
	}

	private boolean publicationEnabled() {
		return Boolean.TRUE.equals(this.jdbc.queryForObject("select enabled from integration_publication", Boolean.class));
	}

	private static Logger root() {
		return ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(Logger.ROOT_LOGGER_NAME);
	}

	private static String render(ILoggingEvent event) {
		StringBuilder text = new StringBuilder(event.getFormattedMessage());
		for (var proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
			text.append('\n').append(proxy.getClassName()).append(": ").append(proxy.getMessage());
		}
		return text.toString();
	}

}
