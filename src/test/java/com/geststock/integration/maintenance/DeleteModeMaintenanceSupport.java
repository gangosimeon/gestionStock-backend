package com.geststock.integration.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.geststock.integration.FeedMaintenance;
import com.geststock.integration.PublicationFlag;
import com.geststock.integration.link.Authorizer;
import com.geststock.integration.link.Link;
import com.geststock.integration.link.LinkRegistry;
import com.geststock.integration.link.RetainedLinks;
import com.geststock.platform.PlatformDb;
import com.geststock.support.PostgresTestConfiguration;
import com.geststock.tenancy.TenantContext;
import com.geststock.tenancy.TenantRef;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contexte commun des tests d'orchestration de la maintenance (lot 3, G20b à G20d) : route activée, purge en mode
 * {@code DELETE} par lots de 1 <b>sur les bases de test seulement</b>, horloge du budget manuelle (aucun délai réel),
 * espions sur les entreprises visitées, la façade du flux, le bail, la liste et le registre des liaisons. Un seul contexte Spring
 * pour toutes les classes qui en héritent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({ PostgresTestConfiguration.class, DeleteModeMaintenanceSupport.ManualTickerConfiguration.class })
@TestPropertySource(properties = { "geststock.integration.maintenance.enabled=true",
		"geststock.integration.maintenance.token-sha256=" + MaintenanceRouteIT.CURRENT_SHA256,
		"geststock.integration.maintenance.calls-per-window=100000",
		"geststock.integration.purge.mode=DELETE", "geststock.integration.purge.batch-size=1",
		// Seuil de l'attente du verrou abaissé : un verrou tenu 900 ms le franchit nettement, sous lock_timeout (2 s).
		"geststock.integration.maintenance.alerts.lock-wait-warning=300ms",
		// Nettoyage limité à 2 liaisons par passage : la reprise au passage suivant se voit avec peu de données.
		"geststock.integration.maintenance.expired-pending-links-per-pass=2",
		"geststock.platform.provisioning.async=false" })
abstract class DeleteModeMaintenanceSupport {

	static final String FEED_STATE_ID = "00000000-0000-0000-0000-000000000001";

	static final String REDIRECT_URI = "https://www.jecreemaboutique.com/integrations/geststock/callback";

	@Autowired
	protected MockMvc mvc;

	@Autowired
	protected PlatformDb platform;

	@Autowired
	protected JdbcTemplate jdbc;

	@Autowired
	protected JsonMapper json;

	@Autowired
	protected ManualTicker ticker;

	@Autowired
	protected IntegrationMaintenanceProperties properties;

	@MockitoSpyBean
	protected LinkRegistry registry;

	@Autowired
	protected PublicationFlag publicationFlag;

	@MockitoSpyBean
	protected MaintenanceTenants tenants;

	@MockitoSpyBean
	protected FeedMaintenance feed;

	@MockitoSpyBean
	protected MaintenanceLease lease;

	@MockitoSpyBean
	protected RetainedLinks retainedLinks;

	/** Entreprise historique de test (base {@code JdbcTemplate} des tests). */
	protected TenantRef legacy() {
		return new TenantRef(
				this.platform.jdbc().sql("select id from platform.company where code = 'test'").query(UUID.class).single(),
				"test");
	}

	/** Un passage par la route ; le rapport JSON. */
	protected JsonNode pass() throws Exception {
		String body = this.mvc.perform(post(MaintenanceRouteIT.ROUTE)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + MaintenanceRouteIT.CURRENT))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		return this.json.readTree(body);
	}

	/** État de maintenance effacé pour ces entreprises : jamais visitées. */
	protected void forgetMaintenanceState(List<UUID> companies) {
		this.platform.jdbc().sql("delete from platform.integration_maintenance_company where company_id in (:ids)")
			.param("ids", companies).update();
	}

	/** Flux de l'entreprise historique vidé, repère et dernier seq à zéro, acquittements effacés. */
	protected void resetLegacyFeed() {
		this.jdbc.update("delete from integration_outbox");
		this.jdbc.update("delete from integration_link_ack");
		this.jdbc.update("""
				insert into integration_feed_state (id) values (?::uuid)
				on conflict (id) do update set last_seq = 0, purged_through_seq = 0""", FEED_STATE_ID);
	}

	/** {@code count} événements non séquencés de l'entreprise historique, écrits il y a 100 jours. */
	protected void oldUnsequencedEvents(int count) {
		for (int i = 0; i < count; i++) {
			this.jdbc.update("""
					insert into integration_outbox (type, operation, object_id, object_version, created_at)
					values ('CATEGORY', 'UPSERT', ?, 1, now() - interval '100 days')""", UUID.randomUUID());
		}
	}

	protected List<Long> legacySeqs() {
		return this.jdbc.queryForList("select seq from integration_outbox where seq is not null order by seq", Long.class);
	}

	protected long legacyPurgedThrough() {
		return this.jdbc.queryForObject("select purged_through_seq from integration_feed_state", Long.class);
	}

	protected long legacyLastSeq() {
		return this.jdbc.queryForObject("select last_seq from integration_feed_state", Long.class);
	}

	/** Client JeCréeMaBoutique de test, avec son adresse de retour ; supprimé par {@link #deleteClient}. */
	protected UUID createClient() {
		UUID client = this.platform.jdbc().sql("""
				insert into platform.integration_client (client_id, name, secret_hash, allowed_scopes, status)
				values (:clientId, 'JeCréeMaBoutique (essai G20b)', :hash, ARRAY['catalog:read']::varchar[], 'ACTIVE')
				returning id""")
			.param("clientId", "jcmb-g20b-" + UUID.randomUUID().toString().substring(0, 8))
			.param("hash", sha256Hex(UUID.randomUUID().toString())).query(UUID.class).single();
		this.platform.jdbc().sql("insert into platform.integration_client_redirect_uri (client_id, redirect_uri) values (:c, :u)")
			.param("c", client).param("u", REDIRECT_URI).update();
		return client;
	}

	protected void deleteClient(UUID client) {
		for (String sql : List.of("""
				delete from platform.integration_authorization_code where request_id in
				  (select id from platform.integration_authorization_request where client_id = :c)""",
				"delete from platform.integration_link where client_id = :c",
				"delete from platform.integration_authorization_request where client_id = :c",
				"delete from platform.integration_client where id = :c")) {
			this.platform.jdbc().sql(sql).param("c", client).update();
		}
	}

	/**
	 * Liaison réellement créée puis activée par {@link LinkRegistry}, dans l'ordre de l'échange du code
	 * ({@code OAuthService}, G13) : drapeau de publication de l'entreprise levé <b>avant</b> l'activation.
	 */
	protected Link activeLink(UUID client, TenantRef company, String shop) {
		Link pending = this.registry.createPending(approvedRequest(client, shop), company.companyId(),
				new Authorizer(UUID.randomUUID(), "admin"), Set.of("catalog:read"));
		TenantContext.runAs(company, () -> this.publicationFlag.set(true));
		return this.registry.activate(pending.id()).link();
	}

	/** Demande d'autorisation approuvée pour cette boutique, prête pour {@link LinkRegistry#createPending}. */
	protected UUID approvedRequest(UUID client, String shop) {
		return this.platform.jdbc().sql("""
				insert into platform.integration_authorization_request (request_uri_hash, client_id, external_shop_id, shop_name,
				  redirect_uri, code_challenge, scopes, state, expires_at, decision, used_at)
				values (:hash, :client, :shop, :name, :redirect, 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM',
				  ARRAY['catalog:read']::varchar[], repeat('s', 43), now() + interval '10 minutes', 'APPROVED', now())
				returning id""")
			.param("hash", sha256Hex(UUID.randomUUID().toString())).param("client", client).param("shop", shop)
			.param("name", "Boutique " + shop).param("redirect", REDIRECT_URI).query(UUID.class).single();
	}

	protected static String sha256Hex(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (java.security.NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	protected static void assertCompanies(JsonNode report, int active, int visited, int skippedUpgrade, int errors,
			int interrupted, int remaining) {
		JsonNode companies = report.get("companies");
		assertThat(companies.get("active").asInt()).as("companies.active").isEqualTo(active);
		assertThat(companies.get("visited").asInt()).as("companies.visited").isEqualTo(visited);
		assertThat(companies.get("skippedUpgrade").asInt()).as("companies.skippedUpgrade").isEqualTo(skippedUpgrade);
		assertThat(companies.get("errors").asInt()).as("companies.errors").isEqualTo(errors);
		assertThat(companies.get("interrupted").asInt()).as("companies.interrupted").isEqualTo(interrupted);
		assertThat(companies.get("remaining").asInt()).as("companies.remaining").isEqualTo(remaining);
	}

	/** Horloge du budget avancée par les tests seulement. */
	static class ManualTicker extends MaintenanceTicker {

		private final AtomicLong now = new AtomicLong(1_000_000_000L);

		@Override
		long nanoTime() {
			return this.now.get();
		}

		void advance(Duration duration) {
			this.now.addAndGet(duration.toNanos());
		}

	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ManualTickerConfiguration {

		@Bean
		@Primary
		ManualTicker manualTicker() {
			return new ManualTicker();
		}

	}

}
