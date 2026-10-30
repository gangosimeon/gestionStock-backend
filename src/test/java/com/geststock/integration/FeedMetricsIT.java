package com.geststock.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.geststock.support.PostgresTestConfiguration;
import com.geststock.tenancy.DefaultTenant;
import com.geststock.tenancy.TenantDataSourceRegistry;
import com.geststock.tenancy.TenantDirectory;
import com.geststock.tenancy.TenantRef;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Métriques du flux d'intégration (lot 1, étape 8 ; décision Q5) sur un vrai PostgreSQL : nombre d'événements sans
 * {@code seq} et âge du plus ancien, par entreprise ; WARN au-delà du seuil ; une base indisponible n'empêche pas
 * les autres ; job désactivé dans le profil de test.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class FeedMetricsIT {

	private static final String FEED_STATE_ID = "00000000-0000-0000-0000-000000000001";

	private static final String MARKER = "contenu-jamais-journalise";

	@Autowired
	private TenantDataSourceRegistry registry;

	@Autowired
	private DefaultTenant defaultTenant;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private ApplicationContext context;

	private TenantRef tenant;

	private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

	private ListAppender<ILoggingEvent> logs;

	private Logger logger;

	@BeforeEach
	void setUp() {
		this.tenant = this.defaultTenant.get().orElseThrow();
		this.logger = (Logger) LoggerFactory.getLogger(FeedMetrics.class);
		this.logs = new ListAppender<>();
		this.logs.start();
		this.logger.addAppender(this.logs);
		resetFeed();
	}

	@AfterEach
	void tearDown() {
		this.logger.detachAppender(this.logs);
		resetFeed();
	}

	@Test
	void theGaugesGiveTheCountAndAgeOfUnsequencedEventsPerCompany() {
		insertUnsequenced(3, Duration.ofMinutes(10));
		insertSequenced();

		metrics(Duration.ofMinutes(30), List.of(this.tenant), this.registry::dataSource).collect();

		assertThat(gauge("integration.outbox.unsequenced", this.tenant)).isEqualTo(3.0);
		assertThat(gauge("integration.outbox.oldest.unsequenced.age", this.tenant)).isBetween(600.0, 660.0);
	}

	@Test
	void noUnsequencedEventMeansZeroCountAndZeroAge() {
		insertSequenced();
		metrics(Duration.ofMinutes(5), List.of(this.tenant), this.registry::dataSource).collect();
		assertThat(gauge("integration.outbox.unsequenced", this.tenant)).isZero();
		assertThat(gauge("integration.outbox.oldest.unsequenced.age", this.tenant)).isZero();
		assertThat(warnings()).isEmpty();
	}

	@Test
	void aWarningIsLoggedBeyondTheThresholdOnlyAndNeverWithEventContent() {
		insertUnsequenced(2, Duration.ofMinutes(10));

		metrics(Duration.ofMinutes(20), List.of(this.tenant), this.registry::dataSource).collect();
		assertThat(warnings()).as("en dessous du seuil").isEmpty();

		metrics(Duration.ofMinutes(5), List.of(this.tenant), this.registry::dataSource).collect();
		assertThat(warnings()).singleElement().satisfies(message -> {
			assertThat(message).contains(this.tenant.companyId().toString()).contains("2");
			assertThat(message).doesNotContain(MARKER);
		});
	}

	@Test
	void aCompanyInErrorDoesNotPreventTheOthers() {
		insertUnsequenced(1, Duration.ofMinutes(1));
		TenantRef broken = new TenantRef(UUID.randomUUID(), "indisponible");
		DataSource unavailable = new AbstractDataSource() {

			@Override
			public java.sql.Connection getConnection() throws SQLException {
				throw new SQLException("base indisponible");
			}

			@Override
			public java.sql.Connection getConnection(String username, String password) throws SQLException {
				throw new SQLException("base indisponible");
			}

		};
		Function<TenantRef, DataSource> dataSources = ref -> ref.equals(broken) ? unavailable
				: this.registry.dataSource(ref);

		metrics(Duration.ofMinutes(5), List.of(broken, this.tenant), dataSources).collect();

		assertThat(gauge("integration.outbox.unsequenced", this.tenant)).isEqualTo(1.0);
		assertThat(warnings()).singleElement().asString().contains(broken.companyId().toString());
	}

	@Test
	void theJobIsDisabledInTheTestProfile() {
		assertThat(this.context.getBeansOfType(FeedMetricsJob.class)).isEmpty();
		assertThat(this.context.getBeansOfType(IntegrationScheduling.class)).isEmpty();
	}

	/**
	 * Propriété vérifiée : la requête des métriques ne parcourt jamais toute la table, un index lui donne directement
	 * les seuls événements sans {@code seq}. Deux index le peuvent, et le choix entre eux dépend des statistiques de la
	 * table, donc du moment où l'autoanalyse passe pendant la suite complète : table jamais analysée ⇒ index partiel
	 * {@code ix_integration_outbox_unsequenced} ; table analysée avec beaucoup d'événements séquencés ⇒ partie NULL de
	 * l'index unique {@code uk_integration_outbox_seq} (condition d'index {@code seq IS NULL}, coût estimé un peu
	 * moindre). Les deux états sont vérifiés, le second rendu déterministe par {@code ANALYZE}.
	 */
	@Test
	void theQueryReadsOnlyUnsequencedEventsThroughAnIndex() {
		assertReadsOnlyUnsequencedEvents(plan());

		// État de la suite complète : beaucoup d'événements séquencés, quelques-uns en attente, statistiques à jour.
		this.jdbc.update("""
				insert into integration_outbox (type, operation, object_id, object_version, seq, created_at)
				select 'CATEGORY', 'UPSERT', gen_random_uuid(), 1, g, now() - interval '1 day' from generate_series(1, 2000) g""");
		insertUnsequenced(35, Duration.ofMinutes(1));
		this.jdbc.execute("analyze integration_outbox");
		assertReadsOnlyUnsequencedEvents(plan());
	}

	// --- Outils ------------------------------------------------------------------------------------------

	/** Plan de la requête des métriques, parcours séquentiel découragé (sur une petite table, il serait légitime). */
	private String plan() {
		List<String> plan = this.transactions.execute(status -> {
			this.jdbc.execute("set local enable_seqscan = off");
			return this.jdbc.queryForList("explain " + FeedMetrics.UNSEQUENCED, String.class);
		});
		System.out.println("Plan de la requête des métriques :\n" + String.join("\n", plan));
		return String.join("\n", plan);
	}

	private static void assertReadsOnlyUnsequencedEvents(String plan) {
		assertThat(plan).as("aucun parcours séquentiel de la table").doesNotContain("Seq Scan");
		assertThat(plan).as("aucun filtrage après lecture").doesNotContain("Filter:");
		assertThat(plan).as("seuls les événements sans seq sont lus, par un index").satisfiesAnyOf(
				p -> assertThat(p).contains("ix_integration_outbox_unsequenced"),
				p -> assertThat(p).contains("uk_integration_outbox_seq").contains("Index Cond: (seq IS NULL)"));
	}

	private FeedMetrics metrics(Duration warnAfter, List<TenantRef> tenants, Function<TenantRef, DataSource> dataSources) {
		TenantDirectory directory = mock(TenantDirectory.class);
		given(directory.activeTenants()).willReturn(tenants);
		return new FeedMetrics(directory, dataSources, this.meters,
				new IntegrationMetricsProperties(true, Duration.ofSeconds(60), warnAfter));
	}

	private double gauge(String name, TenantRef ref) {
		Gauge gauge = this.meters.find(name).tag("company", ref.companyId().toString()).gauge();
		assertThat(gauge).as("jauge %s", name).isNotNull();
		return gauge.value();
	}

	private List<String> warnings() {
		return this.logs.list.stream().filter(event -> event.getLevel() == Level.WARN)
			.map(ILoggingEvent::getFormattedMessage)
			.toList();
	}

	private void insertUnsequenced(int count, Duration age) {
		for (int i = 0; i < count; i++) {
			this.jdbc.update("""
					insert into integration_outbox (type, operation, object_id, object_version, payload, created_at)
					values ('CATEGORY', 'UPSERT', ?, 1, cast(? as jsonb), now() - make_interval(secs => ?))""",
					UUID.randomUUID(), "{\"marker\":\"" + MARKER + "\"}", (double) age.toSeconds());
		}
	}

	private void insertSequenced() {
		this.jdbc.update("""
				insert into integration_outbox (type, operation, object_id, object_version, seq, created_at)
				values ('CATEGORY', 'UPSERT', ?, 1, 1, now() - interval '1 day')""", UUID.randomUUID());
	}

	private void resetFeed() {
		this.jdbc.update("delete from integration_outbox");
		this.jdbc.update("""
				insert into integration_feed_state (id) values (?::uuid)
				on conflict (id) do update set last_seq = 0, purged_through_seq = 0""", FEED_STATE_ID);
	}

}
