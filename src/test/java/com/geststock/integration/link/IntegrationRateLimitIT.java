package com.geststock.integration.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.filter.ForwardedHeaderFilter;

import com.geststock.platform.PlatformDb;
import com.geststock.support.PostgresTestConfiguration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Limitation de débit des routes du client (lot 2, étape G9 ; v13-D4 complété en v14, v14-D8) : compteurs en fenêtre
 * fixe dans la base plateforme. Par IP, avant l'authentification du client, toutes tentatives comprises ; par client,
 * après une authentification réussie seulement (annoncer l'identifiant de JCMB n'épuise pas son quota).
 * {@code /oauth/token} : 10 par minute par IP et par client ; {@code /oauth/par} : 30 par minute par IP et par client.
 * IP lue dans l'en-tête
 * configuré, jamais dans {@code X-Forwarded-For} (ici avec {@code forward-headers-strategy: framework}, comme en
 * production). Valeurs de départ rétablies explicitement : le profil de test les relève pour les autres tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = { "server.forward-headers-strategy=framework",
		"geststock.integration.client-ip-header=" + IntegrationRateLimitIT.IP_HEADER,
		"geststock.integration.rate-limit.window=1m", "geststock.integration.rate-limit.oauth-token-per-client=10",
		"geststock.integration.rate-limit.oauth-token-per-ip=10", "geststock.integration.rate-limit.oauth-par-per-client=30",
		"geststock.integration.rate-limit.oauth-par-per-ip=30" })
@Import(PostgresTestConfiguration.class)
class IntegrationRateLimitIT {

	static final String IP_HEADER = "X-Test-Client-Ip";

	private static final String PAR = "/api/integration/v1/oauth/par";

	private static final String TOKEN = "/api/integration/v1/oauth/token";

	private static final String WRONG = "mauvais-secret-g9";

	private static final Set<String> ALLOWED = Set.of("catalog:read");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private PlatformDb platform;

	@Autowired
	private LinkRegistry registry;

	private TestLinks links;

	private final List<String> keys = new ArrayList<>();

	private ListAppender<ILoggingEvent> logs;

	private Logger root;

	private Level rootLevel;

	@BeforeEach
	void setUp() {
		this.links = new TestLinks(this.platform, this.registry);
		this.root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		this.rootLevel = this.root.getLevel();
		this.root.setLevel(Level.TRACE);
		this.logs = new ListAppender<>();
		this.logs.start();
		this.root.addAppender(this.logs);
		awaitFreshWindow();
	}

	@AfterEach
	void tearDown() {
		this.root.detachAppender(this.logs);
		this.root.setLevel(this.rootLevel);
		for (String key : this.keys) {
			this.platform.jdbc().sql("delete from platform.integration_rate_limit where counter_key = :key").param("key", key)
				.update();
		}
		this.links.cleanUp();
	}

	// --- /oauth/token ---------------------------------------------------------------------------------------

	@Test
	void wrongSecretsAnnouncingTheClientOnlyLimitTheirIpAndTheRealClientStillGetsItsToken() throws Exception {
		String secret = "secret-client-g9-" + UUID.randomUUID();
		TestLinks.Client client = this.links.client(secret, null, ALLOWED);
		ActivatedLink link = this.registry.activate(this.links.pending(client, this.links.legacyCompany(), "shop-g9-a", ALLOWED).id());
		String attacker = ip();
		for (int i = 0; i < 20; i++) {
			MockHttpServletResponse response = this.mvc.perform(token(client.clientId(), WRONG, attacker, link)).andReturn()
				.getResponse();
			assertThat(response.getStatus()).as("tentative " + (i + 1)).isEqualTo(i < 10 ? 401 : 429);
		}
		assertThat(hits("oauth-token:ip:" + attacker)).isEqualTo(20);
		assertThat(hits("oauth-token:client:" + client.clientId())).as("compteur du client intact").isZero();

		// Le vrai client, depuis une autre IP, obtient toujours son jeton.
		this.mvc.perform(token(client.clientId(), secret, ip(), link))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.access_token").exists());
		assertThat(hits("oauth-token:client:" + client.clientId())).isEqualTo(1);
	}

	@Test
	void theEleventhAuthenticatedTokenCallOfTheClientIsRefused() throws Exception {
		String secret = "secret-client-g9-" + UUID.randomUUID();
		TestLinks.Client client = this.links.client(secret, null, ALLOWED);
		ActivatedLink link = this.registry.activate(this.links.pending(client, this.links.legacyCompany(), "shop-g9-b", ALLOWED).id());
		for (int i = 0; i < 10; i++) {
			this.mvc.perform(token(client.clientId(), secret, ip(), link)).andExpect(status().isOk());
		}

		MockHttpServletResponse refused = this.mvc.perform(token(client.clientId(), secret, ip(), link))
			.andExpect(status().isTooManyRequests())
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
			.andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
			.andExpect(jsonPath("$.code").value("RATE_LIMITED"))
			.andExpect(jsonPath("$.status").value(429))
			.andReturn().getResponse();
		assertThat(refused.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		assertThat(Integer.parseInt(refused.getHeader(HttpHeaders.RETRY_AFTER))).isBetween(1, 60);
		assertThat(refused.getContentAsString()).doesNotContain("access_token");
		assertThat(hits("oauth-token:client:" + client.clientId())).isEqualTo(11);
	}

	@Test
	void theIpCounterAddsUpClientsAndTheClientCounterAddsUpIps() throws Exception {
		String ip = ip();
		TestLinks.Client first = this.links.client("secret-a-" + UUID.randomUUID(), null, ALLOWED);
		TestLinks.Client second = this.links.client("secret-b-" + UUID.randomUUID(), null, ALLOWED);
		TestLinks.Client third = this.links.client("secret-c-" + UUID.randomUUID(), null, ALLOWED);
		for (int i = 0; i < 5; i++) {
			this.mvc.perform(token(first.clientId(), WRONG, ip, null)).andExpect(status().isUnauthorized());
			this.mvc.perform(token(second.clientId(), WRONG, ip, null)).andExpect(status().isUnauthorized());
		}
		this.mvc.perform(token(third.clientId(), WRONG, ip, null)).andExpect(status().isTooManyRequests());

		// Compteur du client : appels authentifiés seulement, toutes IP confondues.
		String secret = "secret-d-" + UUID.randomUUID();
		TestLinks.Client roaming = this.links.client(secret, null, ALLOWED);
		ActivatedLink link = this.registry.activate(this.links.pending(roaming, this.links.legacyCompany(), "shop-g9-d", ALLOWED).id());
		String[] ips = { ip(), ip() };
		for (int i = 0; i < 10; i++) {
			this.mvc.perform(token(roaming.clientId(), secret, ips[i % 2], link)).andExpect(status().isOk());
		}
		this.mvc.perform(token(roaming.clientId(), secret, ip(), link)).andExpect(status().isTooManyRequests());
	}

	@Test
	void twentySimultaneousCallsWithALimitOfTenLetExactlyTenThrough() throws Exception {
		TestLinks.Client client = this.links.client("secret-e-" + UUID.randomUUID(), null, ALLOWED);
		String ip = ip();
		int calls = 20;
		CyclicBarrier start = new CyclicBarrier(calls);
		ExecutorService pool = Executors.newFixedThreadPool(calls);
		try {
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < calls; i++) {
				// Même IP, mauvais secret : seul le compteur par IP (10) limite.
				MockHttpServletRequestBuilder request = token(client.clientId(), WRONG, ip, null);
				results.add(pool.submit(() -> {
					start.await(10, TimeUnit.SECONDS);
					return this.mvc.perform(request).andReturn().getResponse().getStatus();
				}));
			}
			int through = 0;
			int refused = 0;
			for (Future<Integer> result : results) {
				int status = result.get(30, TimeUnit.SECONDS);
				if (status == 429) {
					refused++;
				}
				else {
					assertThat(status).isEqualTo(401);
					through++;
				}
			}
			assertThat(through).as("appels passés").isEqualTo(10);
			assertThat(refused).as("appels refusés").isEqualTo(10);
			assertThat(hits("oauth-token:ip:" + ip)).isEqualTo(20);
			assertThat(hits("oauth-token:client:" + client.clientId())).isZero();
		}
		finally {
			pool.shutdownNow();
		}
	}

	// --- /oauth/par -----------------------------------------------------------------------------------------

	@Test
	void theThirtyFirstParCallFromOneIpIsRefusedEvenWithoutValidAuthentication() throws Exception {
		String ip = ip();
		for (int i = 0; i < 30; i++) {
			// Identifiant annoncé différent à chaque appel : seul le compteur par IP peut limiter.
			this.mvc.perform(par("inconnu-" + i + "-" + UUID.randomUUID().toString().substring(0, 8), WRONG, ip))
				.andExpect(status().isUnauthorized());
		}
		MockHttpServletResponse refused = this.mvc.perform(par("inconnu-final", WRONG, ip))
			.andExpect(status().isTooManyRequests())
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
			.andExpect(jsonPath("$.code").value("RATE_LIMITED"))
			.andReturn().getResponse();
		assertThat(Integer.parseInt(refused.getHeader(HttpHeaders.RETRY_AFTER))).isBetween(1, 60);
		assertThat(hits("oauth-par:ip:" + ip)).isEqualTo(31);
	}

	@Test
	void theThirtyFirstAuthenticatedParCallOfTheClientIsRefused() throws Exception {
		String secret = "secret-f-" + UUID.randomUUID();
		TestLinks.Client client = this.links.client(secret, null, ALLOWED);
		for (int i = 0; i < 30; i++) {
			// Authentifié, demande incomplète : 400 invalid_request, mais l'appel compte pour le client.
			this.mvc.perform(par(client.clientId(), secret, ip())).andExpect(status().isBadRequest());
		}
		this.mvc.perform(par(client.clientId(), WRONG, ip())).andExpect(status().isUnauthorized());
		this.mvc.perform(par(client.clientId(), secret, ip()))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("RATE_LIMITED"));
		assertThat(hits("oauth-par:client:" + client.clientId())).as("la tentative au mauvais secret ne compte pas")
			.isEqualTo(31);
	}

	// --- Fenêtres -------------------------------------------------------------------------------------------

	@Test
	void theCounterRestartsInTheNextWindow() throws Exception {
		TestLinks.Client client = this.links.client("secret-g-" + UUID.randomUUID(), null, ALLOWED);
		String ip = ip();
		String key = remember("oauth-par:ip:" + ip);
		// Fenêtre courante (horloge de PostgreSQL) déjà pleine.
		this.platform.jdbc().sql("""
				insert into platform.integration_rate_limit (counter_key, window_start, hits)
				values (:key, to_timestamp(floor(extract(epoch from now()) / 60) * 60), 30)""").param("key", key).update();
		this.mvc.perform(par(client.clientId(), WRONG, ip)).andExpect(status().isTooManyRequests());

		// La fenêtre se termine : la ligne devient celle de la fenêtre précédente.
		this.platform.jdbc().sql("""
				update platform.integration_rate_limit set window_start = window_start - interval '1 minute'
				where counter_key = :key""").param("key", key).update();
		this.mvc.perform(par(client.clientId(), WRONG, ip)).andExpect(status().isUnauthorized());
		assertThat(hits(key)).as("nouvelle fenêtre").isEqualTo(1);
	}

	@Test
	void creatingAWindowRemovesOldWindowsAndKeepsTheCurrentOnes() throws Exception {
		String old = remember("test-g9:old:" + UUID.randomUUID());
		String current = remember("test-g9:current:" + UUID.randomUUID());
		this.platform.jdbc().sql("""
				insert into platform.integration_rate_limit (counter_key, window_start, hits) values
				  (:old, now() - interval '2 days', 3),
				  (:old, now() - interval '3 days', 4),
				  (:current, to_timestamp(floor(extract(epoch from now()) / 60) * 60), 2)""")
			.param("old", old).param("current", current).update();

		TestLinks.Client client = this.links.client("secret-h-" + UUID.randomUUID(), null, ALLOWED);
		this.mvc.perform(par(client.clientId(), WRONG, ip())).andExpect(status().isUnauthorized());

		assertThat(rows(old)).as("fenêtres anciennes supprimées").isZero();
		assertThat(hits(current)).as("fenêtre courante conservée").isEqualTo(2);
	}

	// --- Adresse IP (v14-D8) --------------------------------------------------------------------------------

	@Test
	void aForgedXForwardedForHasNoEffectWhenTheConfiguredHeaderIsPresent() throws Exception {
		String ip = ip();
		String forged = "203.0.113." + (int) (Math.random() * 200 + 1);
		for (int i = 0; i < 10; i++) {
			TestLinks.Client client = this.links.client("secret-i-" + UUID.randomUUID(), null, ALLOWED);
			this.mvc.perform(token(client.clientId(), WRONG, ip, null).header("X-Forwarded-For", forged + "." + i))
				.andExpect(status().isUnauthorized());
		}
		TestLinks.Client last = this.links.client("secret-j-" + UUID.randomUUID(), null, ALLOWED);
		this.mvc.perform(token(last.clientId(), WRONG, ip, null).header("X-Forwarded-For", forged))
			.andExpect(status().isTooManyRequests());
		assertThat(hits("oauth-token:ip:" + ip)).isEqualTo(11);
		assertThat(this.platform.jdbc().sql("select count(*) from platform.integration_rate_limit where counter_key like :key")
			.param("key", "%" + forged + "%").query(Integer.class).single()).isZero();
	}

	@Test
	void withoutTheConfiguredHeaderEveryCallSharesTheUnknownIpKey() throws Exception {
		String unknown = remember("oauth-token:ip:unknown");
		this.platform.jdbc().sql("delete from platform.integration_rate_limit where counter_key = :key").param("key", unknown)
			.update();
		for (int i = 0; i < 10; i++) {
			TestLinks.Client client = this.links.client("secret-k-" + UUID.randomUUID(), null, ALLOWED);
			this.mvc.perform(token(client.clientId(), WRONG, null, null).header("X-Forwarded-For", "198.51.100." + (i + 1)))
				.andExpect(status().isUnauthorized());
		}
		TestLinks.Client last = this.links.client("secret-l-" + UUID.randomUUID(), null, ALLOWED);
		this.mvc.perform(token(last.clientId(), WRONG, null, null)).andExpect(status().isTooManyRequests());
		// Une IP connue garde sa propre limite.
		this.mvc.perform(token(last.clientId(), WRONG, ip(), null)).andExpect(status().isUnauthorized());
		assertThat(hits(unknown)).isEqualTo(11);
	}

	/**
	 * Pourquoi jamais {@code getRemoteAddr()} en production : avec {@code forward-headers-strategy: framework},
	 * {@link ForwardedHeaderFilter} remplace l'adresse de la connexion par la première valeur de
	 * {@code X-Forwarded-For}, que le client écrit lui-même.
	 */
	@Test
	void theFrameworkStrategyTakesTheRemoteAddressFromXForwardedFor() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", TOKEN);
		request.setRemoteAddr("10.0.0.7");
		request.addHeader("X-Forwarded-For", "203.0.113.99, 10.0.0.7");
		AtomicReference<String> seen = new AtomicReference<>();
		new ForwardedHeaderFilter().doFilter(request, new MockHttpServletResponse(),
				(req, res) -> seen.set(req.getRemoteAddr()));
		assertThat(seen.get()).isEqualTo("203.0.113.99");
	}

	// --- Journaux -------------------------------------------------------------------------------------------

	@Test
	void theLogsNameTheCounterAndTheDecisionButNeverTheAuthorizationHeader() throws Exception {
		TestLinks.Client client = this.links.client("secret-m-" + UUID.randomUUID(), null, ALLOWED);
		String ip = ip();
		List<String> headers = new ArrayList<>();
		for (int i = 0; i < 11; i++) {
			MockHttpServletRequestBuilder request = token(client.clientId(), WRONG + i, ip, null);
			headers.add(basic(client.clientId(), WRONG + i).substring("Basic ".length()));
			this.mvc.perform(request);
		}
		List<String> messages = this.logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
		assertThat(messages).as("décision de refus journalisée avec la clé")
			.anyMatch(message -> message.contains("oauth-token:ip:" + ip) && message.contains("429"));
		assertThat(messages).as("aucun en-tête Authorization").allSatisfy(message -> {
			for (String value : headers) {
				assertThat(message).doesNotContain(value);
			}
			assertThat(message).doesNotContain(WRONG);
		});
	}

	// --- Outils ---------------------------------------------------------------------------------------------

	private MockHttpServletRequestBuilder token(String clientId, String secret, String ip, ActivatedLink link) {
		MockHttpServletRequestBuilder request = post(TOKEN).contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.header(HttpHeaders.AUTHORIZATION, basic(clientId, secret))
			.param("grant_type", "client_credentials")
			.param("link_id", link != null ? link.link().id().toString() : UUID.randomUUID().toString())
			.param("link_secret", link != null ? link.secret() : "inconnu");
		remember("oauth-token:client:" + clientId);
		if (ip != null) {
			request.header(IP_HEADER, ip);
			remember("oauth-token:ip:" + ip);
		}
		return request;
	}

	private MockHttpServletRequestBuilder par(String clientId, String secret, String ip) {
		remember("oauth-par:client:" + clientId);
		remember("oauth-par:ip:" + ip);
		return post(PAR).contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.header(HttpHeaders.AUTHORIZATION, basic(clientId, secret))
			.header(IP_HEADER, ip)
			.param("response_type", "code");
	}

	/**
	 * Fenêtres fixes d'une minute : un test qui chevauche deux fenêtres verrait son compteur repartir. On attend donc la
	 * fenêtre suivante s'il reste moins de 20 secondes (horloge de PostgreSQL).
	 */
	private void awaitFreshWindow() {
		double remaining = this.platform.jdbc().sql("select 60 - mod(extract(epoch from clock_timestamp()), 60)::float8")
			.query(Double.class).single();
		if (remaining < 20) {
			try {
				Thread.sleep((long) (remaining * 1000) + 200);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private String remember(String key) {
		this.keys.add(key);
		return key;
	}

	private int hits(String key) {
		return this.platform.jdbc().sql("""
				select coalesce(sum(hits), 0) from platform.integration_rate_limit
				where counter_key = :key and window_start = to_timestamp(floor(extract(epoch from now()) / 60) * 60)""")
			.param("key", key)
			.query(Integer.class).single();
	}

	private int rows(String key) {
		return this.platform.jdbc().sql("select count(*) from platform.integration_rate_limit where counter_key = :key")
			.param("key", key).query(Integer.class).single();
	}

	/** Adresse de la plage de test 198.18.0.0/15 (RFC 2544), propre à l'appel : aucun compteur partagé entre tests. */
	private static String ip() {
		UUID random = UUID.randomUUID();
		return "198.18." + (Math.abs(random.getMostSignificantBits()) % 256) + "." + (Math.abs(random.getLeastSignificantBits()) % 256);
	}

	private static String basic(String clientId, String secret) {
		return "Basic " + Base64.getEncoder().encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.UTF_8));
	}

}
