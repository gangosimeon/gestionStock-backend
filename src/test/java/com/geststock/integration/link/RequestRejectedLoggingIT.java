package com.geststock.integration.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import com.geststock.platform.PlatformDb;
import com.geststock.support.PostgresTestConfiguration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.json.JsonMapper;

/**
 * Paramètre refusé par le pare-feu de Spring Security (nom ou valeur avec un caractère de contrôle), sur un vrai serveur
 * HTTP : MockMvc n'a ni la chaîne de query réelle ni le {@code DispatcherServlet} de production. Une route d'intégration
 * ({@code GET /catalog/changes}) et une route principale ({@code GET /api/platform/companies}), authentifiées, aux
 * niveaux de journal de production puis en DEBUG et TRACE : même réponse aux deux niveaux ({@code 400} sur la route
 * d'intégration et sur une route principale qui lie un objet de filtre ; comportement existant sinon), jamais de
 * {@code 500}, et le nom ou la valeur refusés n'apparaissent ni dans la réponse ni dans les journaux (en DEBUG, hors
 * ligne de requête : {@link #REQUEST_LINE_LOGGERS}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RequestRejectedLoggingIT {

	private static final String SUPER_ADMIN = "it_rejected_superadmin";

	private static final String SUPER_ADMIN_PASSWORD = "Plateforme-Test-2026";

	private static final String USER = "it_rejected_admin";

	private static final String USER_PASSWORD = "Optique2026";

	/**
	 * Loggers que la configuration limite parce qu'ils citent les valeurs reçues : laissés à leur niveau configuré par le
	 * scénario DEBUG, qui vérifie justement que ces limites protègent.
	 */
	private static final Set<String> CAPPED_LOGGERS = Set.of("org.postgresql", "org.springframework.web.method.HandlerMethod",
			"org.springframework.web.servlet.DispatcherServlet");

	/**
	 * Loggers qui, en DEBUG ou TRACE, écrivent la ligne de requête complète (chemin et query) de <b>toute</b> requête,
	 * refusée ou non : limite assumée, ces niveaux ne sont jamais activés en production. Leur contenu n'est pas vérifié
	 * par le scénario DEBUG ; tous les autres journaux le sont.
	 */
	private static final Set<String> REQUEST_LINE_LOGGERS = Set.of("org.apache.coyote.http11.Http11InputBuffer",
			"org.apache.tomcat.util.http.Parameters", "org.springframework.security.web.FilterChainProxy",
			"org.springframework.security.web.access.intercept.RequestMatcherDelegatingAuthorizationManager");

	@Autowired
	private Environment environment;

	@Autowired
	private PlatformDb platform;

	@Autowired
	private LinkRegistry registry;

	@Autowired
	private IntegrationTokenService tokens;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JsonMapper json;

	@Autowired
	private JdbcTemplate jdbc;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	private TestLinks links;

	private String linkToken;

	private String adminToken;

	/** Utilisateur ADMIN de l'entreprise de test historique (routes principales {@code /api/**}). */
	private String userToken;

	private final Map<Logger, Level> levels = new LinkedHashMap<>();

	private ListAppender<ILoggingEvent> logs;

	@BeforeAll
	void tokens() throws Exception {
		this.links = new TestLinks(this.platform, this.registry);
		TestLinks.Client client = this.links.client();
		Link link = this.links.active(client, this.links.legacyCompany(), "shop-rejected-" + UUID.randomUUID(),
				Set.of("catalog:read"));
		this.linkToken = this.tokens.issue(link.id(), client.clientId(), Set.of("catalog:read")).value();
		this.platform.jdbc().sql("delete from platform.platform_admin where username = :u").param("u", SUPER_ADMIN).update();
		this.platform.jdbc()
			.sql("insert into platform.platform_admin (username, full_name, password_hash, must_change_password) values (:u, 'IT', :h, false)")
			.param("u", SUPER_ADMIN).param("h", this.passwordEncoder.encode(SUPER_ADMIN_PASSWORD)).update();
		HttpResponse<String> login = this.http.send(HttpRequest.newBuilder(uri("/api/platform/auth/login"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(
					"{\"username\": \"" + SUPER_ADMIN + "\", \"password\": \"" + SUPER_ADMIN_PASSWORD + "\"}"))
			.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(login.statusCode()).isEqualTo(200);
		this.adminToken = this.json.readTree(login.body()).get("accessToken").asString();

		this.jdbc.update("delete from app_user where username = ?", USER);
		UUID user = this.jdbc.queryForObject("""
				insert into app_user (username, last_name, must_change_password, password_hash)
				values (?, 'Essai pare-feu', false, ?) returning id""", UUID.class, USER, this.passwordEncoder.encode(USER_PASSWORD));
		this.jdbc.update("insert into user_role (user_id, role_id) select ?, id from role where code = 'ADMIN'", user);
		HttpResponse<String> userLogin = this.http.send(HttpRequest.newBuilder(uri("/api/auth/login"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString("{\"username\": \"" + USER + "\", \"password\": \"" + USER_PASSWORD + "\"}"))
			.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(userLogin.statusCode()).isEqualTo(200);
		this.userToken = this.json.readTree(userLogin.body()).get("accessToken").asString();
	}

	@AfterEach
	void restoreLevels() {
		LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
		if (this.logs != null) {
			context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).detachAppender(this.logs);
		}
		this.levels.forEach(Logger::setLevel);
		this.levels.clear();
	}

	@AfterAll
	void cleanUp() {
		this.links.cleanUp();
		this.platform.jdbc().sql("delete from platform.platform_admin where username = :u").param("u", SUPER_ADMIN).update();
		this.jdbc.update("delete from user_role where user_id in (select id from app_user where username = ?)", USER);
		this.jdbc.update("delete from refresh_token where user_id in (select id from app_user where username = ?)", USER);
		this.jdbc.update("delete from app_user where username = ?", USER);
	}

	@Test
	void atProductionLogLevelsARefusedParameterIsA400WithoutTraceOfIt() throws Exception {
		capture(false);
		refuseEverywhere();
	}

	@Test
	void atDebugAndTraceLevelsARefusedParameterIsStillA400WithoutTraceOfIt() throws Exception {
		capture(true);
		refuseEverywhere();
	}

	private void refuseEverywhere() throws Exception {
		List<String> markers = new ArrayList<>();
		// Route d'intégration : nom puis valeur refusés (pare-feu, puis contrôle de la route).
		markers.add(send("/api/integration/v1/catalog/changes?after=0&%s%%01=1", this.linkToken, 400));
		markers.add(send("/api/integration/v1/catalog/changes?after=0&limit=%s%%01", this.linkToken, 400));
		// Route principale, comportement inchangé quel que soit le niveau des journaux : un nom inconnu est ignoré, et le
		// pare-feu ne refuse pas une valeur (seulement les noms) ; la route la reçoit comme avant.
		markers.add(send("/api/platform/companies?%s%%01=1", this.adminToken, 200));
		// Valeur acceptée par le pare-feu et transmise à la recherche : hors du périmètre des paramètres refusés (statut
		// et corps vérifiés, journaux non).
		send("/api/platform/companies?q=%s%%01", this.adminToken, 200);
		// Route principale qui lie un objet de filtre (@ParameterObject) : la liaison lit tous les noms de paramètres, le
		// pare-feu refuse le nom pendant la lecture des arguments.
		markers.add(send("/api/audit-logs?%s%%01=1", this.userToken, 400));
		// Refus du pare-feu avant toute route (%00 dans le chemin) : 400 sans corps (v15-D2), par le gestionnaire de rejet.
		markers.add(send("/api/platform/companies/%s%%00", this.adminToken, 400));
		markers.add(send("/api/integration/v1/catalog/changes%%00%s?after=0", this.linkToken, 400));

		List<String> messages = this.logs.list.stream()
			.filter(event -> !REQUEST_LINE_LOGGERS.contains(event.getLoggerName()))
			.map(RequestRejectedLoggingIT::render)
			.toList();
		assertThat(messages).as("aucun nom ni valeur refusés dans les journaux")
			.allSatisfy(message -> markers.forEach(marker -> assertThat(message).doesNotContain(marker)));
		assertThat(this.logs.list).as("aucune erreur journalisée").noneMatch(event -> event.getLevel() == Level.ERROR);
	}

	/**
	 * Envoie la requête avec un marqueur unique à la place de {@code %s} : statut attendu, marqueur absent du corps, aucun
	 * message d'exception exposé.
	 */
	private String send(String pathAndQuery, String token, int expectedStatus) throws Exception {
		String marker = "rejete" + UUID.randomUUID().toString().substring(0, 8);
		HttpResponse<String> response = this.http.send(HttpRequest.newBuilder(uri(pathAndQuery.formatted(marker)))
			.header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as(pathAndQuery).isEqualTo(expectedStatus);
		assertThat(response.body()).as(pathAndQuery + " : corps").doesNotContain(marker).doesNotContain("Exception");
		return marker;
	}

	/**
	 * Journaux capturés. {@code debug} : tous les loggers en TRACE sauf ceux que la configuration limite ; sinon racine à
	 * INFO, comme en production.
	 */
	private void capture(boolean debug) {
		LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
		for (Logger logger : context.getLoggerList()) {
			boolean root = Logger.ROOT_LOGGER_NAME.equals(logger.getName());
			if ((logger.getLevel() != null || root) && !CAPPED_LOGGERS.contains(logger.getName())) {
				this.levels.put(logger, logger.getLevel());
				logger.setLevel(debug ? Level.TRACE : (root ? Level.INFO : logger.getLevel()));
			}
		}
		this.logs = new ListAppender<>();
		this.logs.start();
		context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).addAppender(this.logs);
	}

	private URI uri(String pathAndQuery) {
		return URI.create("http://localhost:" + this.environment.getProperty("local.server.port") + pathAndQuery);
	}

	private static String render(ILoggingEvent event) {
		StringBuilder text = new StringBuilder(event.getFormattedMessage());
		for (var proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
			text.append('\n').append(proxy.getClassName()).append(": ").append(proxy.getMessage());
		}
		return text.toString();
	}

}
