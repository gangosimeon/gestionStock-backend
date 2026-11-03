package com.geststock.integration.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

import com.geststock.integration.link.Link;
import com.geststock.integration.link.SuspensionReason;
import com.geststock.tenancy.TenantRef;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;

/**
 * Mesures et alertes d'un passage de maintenance, à travers la route (lot 3, G20c ; G20-D7, D11, D15 ; décisions C1 à
 * C8 ; plan § 33.4 et § 33.5), sur l'entreprise historique avec de vraies liaisons : dates vieillies directement dans
 * les tables (base plateforme pour l'activation et la suspension, base de l'entreprise pour les événements et les
 * acquittements).
 *
 * <ul>
 * <li>Une alerte de consommateur au plus par liaison ; alertes regroupées par type et gravité dans le rapport, sans
 * identifiant ; une alerte par ligne de journal, avec les identifiants techniques.</li>
 * <li>Mesures prises même quand la purge s'arrête faute de budget (C3).</li>
 * <li>Une erreur pendant les mesures : {@code COMPANY_ERROR}, son message ni dans la réponse ni dans les journaux.</li>
 * </ul>
 */
class MaintenanceAlertsIT extends DeleteModeMaintenanceSupport {

	@Autowired
	private MaintenanceCompanies companies;

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

	// --- Aucune alerte -------------------------------------------------------------------------------------------

	@Test
	void aSuspendedLinkAndALinkWithNothingPendingRaiseNoAlert() throws Exception {
		Link suspended = activeLink(this.client, this.company, "shop-g20c-suspendue");
		antedateActivation(suspended, "48 hours");
		suspendSince(suspended, "2 days");
		Link idle = activeLink(this.client, this.company, "shop-g20c-a-jour");
		sequenced(1, 3, "10 hours");
		acknowledge(idle, 3, "10 hours");

		JsonNode report = pass();
		assertThat(report.get("status").asString()).isEqualTo("COMPLETED");
		assertThat(alerts(report)).isEmpty();
		assertThat(report.get("links").get("active").asInt()).isEqualTo(1);
		assertThat(report.get("links").get("suspended").asInt()).isEqualTo(1);
	}

	// --- Règles de liaison ----------------------------------------------------------------------------------------

	@Test
	void aLinkThatNeverAcknowledgedWarnsAfterAnHourThenIsCriticalAfterADay() throws Exception {
		Link link = activeLink(this.client, this.company, "shop-g20c-sans-acquittement");
		antedateActivation(link, "2 hours");
		assertThat(alerts(pass())).containsExactly("NO_FIRST_ACK/WARNING/1");
		antedateActivation(link, "25 hours");
		assertThat(alerts(pass())).containsExactly("NO_FIRST_ACK/CRITICAL/1");
	}

	@Test
	void unsequencedEventsAndNoActivityAreRaisedTogetherAndUnsequencedEventsCountOnceForTwoLinks() throws Exception {
		Link first = activeLink(this.client, this.company, "shop-g20c-l1");
		Link second = activeLink(this.client, this.company, "shop-g20c-l2");
		sequenced(1, 3, "10 minutes");
		acknowledge(first, 1, "2 hours");
		acknowledge(second, 1, "2 hours");
		unsequenced(2, "10 minutes");

		JsonNode report = pass();
		assertThat(alerts(report)).as("une alerte de liaison chacune, une seule alerte d'entreprise")
			.containsExactly("NO_ACTIVITY/WARNING/2", "UNSEQUENCED_EVENTS/WARNING/1");
		assertThat(warnings()).anySatisfy(line -> assertThat(line).contains("NO_ACTIVITY").contains(first.id().toString())
			.contains(this.company.companyId().toString()));
		assertThat(warnings()).anySatisfy(line -> assertThat(line).contains("UNSEQUENCED_EVENTS")
			.contains(this.company.companyId().toString()));
	}

	@Test
	void aRecentAcknowledgementWithAnOldPendingEventIsAStalledConsumer() throws Exception {
		Link link = activeLink(this.client, this.company, "shop-g20c-bloque");
		sequenced(1, 3, "7 hours");
		acknowledge(link, 1, "5 minutes");
		assertThat(alerts(pass())).containsExactly("CONSUMER_STALLED/WARNING/1");
	}

	@Test
	void aLinkSuspendedForMoreThanThirtyDaysWarnsThatItBlocksThePurge() throws Exception {
		Link link = activeLink(this.client, this.company, "shop-g20c-longue-suspension");
		antedateActivation(link, "60 days");
		suspendSince(link, "31 days");
		JsonNode report = pass();
		assertThat(alerts(report)).containsExactly("PURGE_BLOCKED_BY_SUSPENSION/WARNING/1");
		assertThat(report.get("links").get("suspended").asInt()).isEqualTo(1);
	}

	// --- Règles d'entreprise --------------------------------------------------------------------------------------

	@Test
	void aCompanyInterruptedTwiceInARowIsCritical() throws Exception {
		this.companies.markStarted(this.company.companyId(), 700_001);
		this.companies.markStarted(this.company.companyId(), 700_002);
		JsonNode report = pass();
		assertThat(alerts(report)).containsExactly("COMPANY_INTERRUPTED/CRITICAL/1");
		assertCompanies(report, 1, 1, 0, 0, 1, 0);
	}

	@Test
	void anErrorDuringTheMeasuresIsACompanyErrorWhoseMessageLeaksNowhere() throws Exception {
		String marker = "marqueur-mesures-" + UUID.randomUUID();
		doThrow(new IllegalStateException(marker)).when(this.feed).measure(any());

		String body = this.mvc.perform(post(MaintenanceRouteIT.ROUTE)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + MaintenanceRouteIT.CURRENT))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		JsonNode report = this.json.readTree(body);
		assertThat(report.get("status").asString()).isEqualTo("COMPLETED");
		assertThat(alerts(report)).containsExactly("COMPANY_ERROR/CRITICAL/1");
		assertCompanies(report, 1, 1, 0, 1, 0, 0);
		assertThat(body).doesNotContain(marker).doesNotContain("IllegalStateException");
		assertThat(List.copyOf(this.logs.list)).as("message de l'erreur absent des journaux")
			.allSatisfy(event -> assertThat(render(event)).doesNotContain(marker));
	}

	@Test
	void aLockWaitBeyondTheThresholdIsReportedAndWarns() throws Exception {
		sequenced(1, 5, "100 days");
		CountDownLatch locked = new CountDownLatch(1);
		ExecutorService thread = Executors.newSingleThreadExecutor();
		try {
			// Verrou de la ligne d'état du flux tenu 900 ms par une autre transaction (seuil de test : 300 ms ;
			// lock_timeout : 2 s).
			Future<?> holder = thread.submit(() -> {
				try (Connection connection = this.jdbc.getDataSource().getConnection()) {
					connection.setAutoCommit(false);
					try (Statement statement = connection.createStatement()) {
						statement.executeQuery("select 1 from integration_feed_state for update");
					}
					locked.countDown();
					Thread.sleep(900);
					connection.commit();
				}
				return null;
			});
			assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
			JsonNode report = pass();
			holder.get(30, TimeUnit.SECONDS);

			assertThat(report.get("purge").get("maxLockWaitMs").asLong()).isBetween(300L, 2000L);
			assertThat(report.get("purge").get("deleted").asLong()).isEqualTo(4);
			assertThat(alerts(report)).containsExactly("LOCK_WAIT/WARNING/1");
		}
		finally {
			thread.shutdownNow();
		}
	}

	// --- Mesures malgré un budget épuisé (C3) ----------------------------------------------------------------------

	@Test
	void aCompanyWhosePurgeEndsPartiallyIsStillMeasured() throws Exception {
		Link link = activeLink(this.client, this.company, "shop-g20c-partielle");
		sequenced(1, 6, "100 days");
		acknowledge(link, 4, "2 hours");
		doAnswer(invocation -> {
			this.ticker.advance(this.properties.budget());
			return invocation.callRealMethod();
		}).when(this.feed).purge(any());

		JsonNode report = pass();
		assertThat(report.get("status").asString()).isEqualTo("PARTIAL");
		assertThat(report.get("purge").get("deleted").asLong()).as("premier lot seulement").isEqualTo(1);
		assertThat(alerts(report)).containsExactly("NO_ACTIVITY/WARNING/1");
	}

	// --- Rapport (G20-D11) ---------------------------------------------------------------------------------------

	@Test
	void theReportCarriesAlertsAndLinksButNoIdentifier() throws Exception {
		Link link = activeLink(this.client, this.company, "shop-g20c-rapport");
		antedateActivation(link, "2 hours");
		String body = this.mvc.perform(post(MaintenanceRouteIT.ROUTE)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + MaintenanceRouteIT.CURRENT))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		JsonNode report = this.json.readTree(body);

		assertThat(report.propertyNames()).containsExactlyInAnyOrder("status", "mode", "generation", "durationMs",
				"companies", "purge", "links", "cleanup", "alerts", "authFailures");
		assertThat(report.get("purge").propertyNames()).containsExactlyInAnyOrder("eligible", "deleted", "batches", "blocked",
				"maxLockWaitMs");
		assertThat(report.get("links").propertyNames()).containsExactlyInAnyOrder("active", "suspended");
		assertThat(report.get("alerts")).singleElement()
			.satisfies(alert -> assertThat(alert.propertyNames()).containsExactlyInAnyOrder("type", "severity", "count"));
		assertThat(body).doesNotContain(this.company.companyId().toString()).doesNotContain(link.id().toString())
			.doesNotContain(this.client.toString()).doesNotContain("shop-g20c-rapport").doesNotContain("Boutique");
	}

	// --- Outils --------------------------------------------------------------------------------------------------

	private void antedateActivation(Link link, String age) {
		this.platform.jdbc().sql("update platform.integration_link set activated_at = now() - cast(:age as interval) where id = :id")
			.param("age", age).param("id", link.id()).update();
	}

	private void suspendSince(Link link, String age) {
		this.registry.suspend(link.id(), SuspensionReason.MANUAL);
		this.platform.jdbc().sql("update platform.integration_link set suspended_at = now() - cast(:age as interval) where id = :id")
			.param("age", age).param("id", link.id()).update();
	}

	/** Événements séquencés {@code from} à {@code through}, écrits il y a {@code age} ; {@code last_seq} suivi. */
	private void sequenced(long from, long through, String age) {
		for (long seq = from; seq <= through; seq++) {
			this.jdbc.update("""
					insert into integration_outbox (type, operation, object_id, object_version, seq, created_at)
					values ('CATEGORY', 'UPSERT', ?, 1, ?, now() - cast(? as interval))""", UUID.randomUUID(), seq, age);
		}
		this.jdbc.update("update integration_feed_state set last_seq = greatest(last_seq, ?)", through);
	}

	private void unsequenced(int count, String age) {
		for (int i = 0; i < count; i++) {
			this.jdbc.update("""
					insert into integration_outbox (type, operation, object_id, object_version, created_at)
					values ('CATEGORY', 'UPSERT', ?, 1, now() - cast(? as interval))""", UUID.randomUUID(), age);
		}
	}

	private void acknowledge(Link link, long seq, String age) {
		this.jdbc.update("""
				insert into integration_link_ack (link_id, acknowledged_seq, acknowledged_at)
				values (?, ?, now() - cast(? as interval))""", link.id(), seq, age);
	}

	/** Alertes du rapport, « TYPE/GRAVITÉ/nombre », dans l'ordre du rapport. */
	private static List<String> alerts(JsonNode report) {
		List<String> alerts = new ArrayList<>();
		report.get("alerts").forEach(alert -> alerts.add(alert.get("type").asString() + "/" + alert.get("severity").asString()
				+ "/" + alert.get("count").asInt()));
		return alerts;
	}

	private List<String> warnings() {
		return List.copyOf(this.logs.list).stream().filter(event -> event.getLevel() == Level.WARN)
			.map(ILoggingEvent::getFormattedMessage).toList();
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
