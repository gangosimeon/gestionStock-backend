package com.geststock.integration.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.common.security.Permissions;
import com.geststock.platform.PlatformDb;
import com.geststock.platform.provisioning.ProvisionerAccess;
import com.geststock.role.RoleService;
import com.geststock.security.CurrentUser;
import com.geststock.security.CurrentUserAuthentication;
import com.geststock.role.dto.UpdateRoleRequest;
import com.geststock.stock.StockFixtures;
import com.geststock.support.PostgresTestConfiguration;
import com.geststock.tenancy.TenantContext;
import com.geststock.tenancy.TenantDataSourceRegistry;
import com.geststock.tenancy.TenantRef;
import com.geststock.user.UserService;
import com.geststock.user.dto.UpdateUserRequest;
import com.geststock.warehouse.WarehouseService;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.json.JsonMapper;

/**
 * Suspension {@code AUTHORIZER_LOST_ACCESS} (lot 2, étape G4b ; v11-D8, v14-D10) : la personne responsable d'une
 * liaison garde ses droits tant que son compte est actif, qu'elle détient {@code integration:manage} et qu'elle a accès
 * à chacun des dépôts de la liaison. Chaque changement qui peut retirer un droit (désactivation ou suppression d'un
 * utilisateur, ses rôles, ses dépôts, les permissions d'un rôle, le dépôt principal) réévalue les liaisons actives de
 * l'entreprise <b>après le commit</b> ; une liaison qui a perdu son responsable est suspendue, sans changement silencieux
 * du périmètre. Déclencheurs appelés par les services réels, au nom d'un administrateur.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "geststock.platform.provisioning.async=false")
@Import({ PostgresTestConfiguration.class, LinkAuthorizerAccessIT.LostAccessTestController.class })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LinkAuthorizerAccessIT {

	private static final String SUPER_ADMIN = "it_g4b_superadmin";

	private static final String SUPER_ADMIN_PASSWORD = "Plateforme-Test-2026";

	private static final String CATALOG = "/api/integration/v1/test-lost-access/catalog";

	private static final Set<String> SCOPES = Set.of("catalog:read");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private PlatformDb platform;

	/** Espion : base plateforme indisponible pendant la réévaluation. */
	@MockitoSpyBean
	private LinkRegistry registry;

	@Autowired
	private LinkAuthorizerWatcher watcher;

	@Autowired
	private LinkConsentService consent;

	@Autowired
	private UserService users;

	@Autowired
	private RoleService roles;

	@Autowired
	private WarehouseService warehouses;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private TenantDataSourceRegistry pools;

	@Autowired
	private ProvisionerAccess provisioner;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JsonMapper json;

	private TestLinks links;

	private TenantRef companyA;

	private TenantRef companyB;

	private UUID mainA;

	private UUID storeA;

	private UUID otherStoreA;

	/** Administrateur qui fait les changements de droits (toutes les permissions, warehouse:all). */
	private UUID actor;

	private String clientSecret;

	private TestLinks.Client client;

	private final List<UUID> createdUsers = new ArrayList<>();

	private final List<UUID> createdRoles = new ArrayList<>();

	private final List<UUID> createdCompanies = new ArrayList<>();

	private ListAppender<ILoggingEvent> warnings;

	@BeforeAll
	void provision() throws Exception {
		this.links = new TestLinks(this.platform, this.registry);
		this.companyA = new TenantRef(this.links.legacyCompany(), "test");
		this.companyB = provisionCompanyB();
		String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
		this.mainA = this.jdbc.queryForObject("select id from warehouse where main", UUID.class);
		this.storeA = this.jdbc.queryForObject("insert into warehouse (code, name) values (?, 'G4b magasin 1') returning id",
				UUID.class, "G4B-S1-" + suffix);
		this.otherStoreA = this.jdbc.queryForObject("insert into warehouse (code, name) values (?, 'G4b magasin 2') returning id",
				UUID.class, "G4B-S2-" + suffix);
		this.actor = user("ADMIN");
	}

	@BeforeEach
	void setUp() {
		this.clientSecret = "secret-g4b-" + UUID.randomUUID();
		this.client = this.links.client(this.clientSecret, null, SCOPES);
		Logger logger = (Logger) LoggerFactory.getLogger(LinkAuthorizerWatcher.class);
		this.warnings = new ListAppender<>();
		this.warnings.start();
		logger.addAppender(this.warnings);
	}

	@AfterEach
	void tearDown() {
		((Logger) LoggerFactory.getLogger(LinkAuthorizerWatcher.class)).detachAppender(this.warnings);
		this.links.cleanUp();
		this.jdbc.update("delete from integration_link_warehouse where warehouse_id in (?, ?, ?)", this.mainA, this.storeA,
				this.otherStoreA);
	}

	@AfterAll
	void cleanUp() throws Exception {
		for (UUID user : this.createdUsers) {
			this.jdbc.update("delete from app_user where id = ?", user);
		}
		for (UUID role : this.createdRoles) {
			this.jdbc.update("delete from role where id = ?", role);
		}
		this.jdbc.update("delete from warehouse where id in (?, ?)", this.storeA, this.otherStoreA);
		dropCompanyB();
	}

	// --- Déclencheurs -------------------------------------------------------------------------------------------

	@Test
	void deactivatingTheResponsibleSuspendsItsLinksOnly() throws Exception {
		UUID responsible = user(manager(), this.storeA);
		Scenario scenario = scenario(responsible, this.storeA);

		asActor(() -> this.users.setActive(responsible, false));

		scenario.assertOnlyTheResponsiblesLinkIsSuspended();
	}

	@Test
	void deletingTheResponsibleSuspendsItsLinks() throws Exception {
		UUID responsible = user(manager(), this.storeA);
		Scenario scenario = scenario(responsible, this.storeA);

		asActor(() -> {
			this.users.delete(responsible);
			return null;
		});

		scenario.assertOnlyTheResponsiblesLinkIsSuspended();
	}

	@Test
	void rolesWithoutIntegrationManageSuspendTheLinks() throws Exception {
		UUID responsible = user(manager(), this.storeA);
		Scenario scenario = scenario(responsible, this.storeA);

		asActor(() -> this.users.assignRoles(responsible, List.of("CAISSIER")));

		scenario.assertOnlyTheResponsiblesLinkIsSuspended();
	}

	@Test
	void losingAWarehouseOfTheLinkSuspendsIt() throws Exception {
		UUID responsible = user(manager(), this.storeA, this.otherStoreA);
		Scenario scenario = scenario(responsible, this.storeA, this.otherStoreA);

		asActor(() -> this.users.update(responsible, update(responsible, roleCodesOf(responsible), this.otherStoreA)));

		scenario.assertOnlyTheResponsiblesLinkIsSuspended();
	}

	@Test
	void aRoleThatLosesIntegrationManageSuspendsTheLinksOfEveryHolder() throws Exception {
		String role = manager();
		UUID first = user(role, this.storeA);
		UUID second = user(role, this.storeA);
		Scenario scenario = scenario(first, this.storeA);
		ActivatedLink secondLink = link(second, this.companyA, "shop-g4b-second", this.storeA);

		asActor(() -> this.roles.update(roleId(role), new UpdateRoleRequest("Sans liaisons", null, List.of("stock:read"))));

		scenario.assertOnlyTheResponsiblesLinkIsSuspended();
		assertSuspended(secondLink.link().id());
	}

	@Test
	void withdrawingWarehouseAllSuspendsALinkOutsideTheRemainingWarehouses() throws Exception {
		String role = role(Permissions.INTEGRATION_MANAGE, Permissions.WAREHOUSE_ALL);
		UUID responsible = user(role);
		Scenario scenario = scenario(responsible, this.otherStoreA);

		// Sans warehouse:all ni affectation, il ne lui reste que le dépôt principal.
		asActor(() -> this.roles.update(roleId(role), new UpdateRoleRequest("Sans tous les dépôts", null,
				List.of(Permissions.INTEGRATION_MANAGE))));

		scenario.assertOnlyTheResponsiblesLinkIsSuspended();
	}

	@Test
	void changingTheMainWarehouseSuspendsTheLinksOfUnassignedResponsibles() throws Exception {
		UUID responsible = user(manager());
		Scenario scenario = scenario(responsible, this.mainA);
		try {
			asActor(() -> this.warehouses.setMain(this.storeA));
			scenario.assertOnlyTheResponsiblesLinkIsSuspended();
		}
		finally {
			asActor(() -> this.warehouses.setMain(this.mainA));
		}
	}

	// --- Ce qui ne suspend rien ----------------------------------------------------------------------------------

	@Test
	void aChangeThatRemovesNoRightSuspendsNothing() throws Exception {
		UUID responsible = user(manager(), this.storeA);
		ActivatedLink link = link(responsible, this.companyA, "shop-g4b-nothing", this.storeA);

		asActor(() -> this.users.update(responsible, update(responsible, roleCodesOf(responsible), this.storeA, this.otherStoreA)));
		asActor(() -> this.users.assignRoles(responsible, List.of(roleCodesOf(responsible).get(0), "CAISSIER")));

		assertActive(link.link().id());
	}

	@Test
	void aRolledBackChangeSuspendsNothing() throws Exception {
		UUID responsible = user(manager(), this.storeA);
		ActivatedLink link = link(responsible, this.companyA, "shop-g4b-rollback", this.storeA);

		asActor(() -> this.transactions.execute(status -> {
			this.users.setActive(responsible, false);
			status.setRollbackOnly();
			return null;
		}));

		assertThat(this.jdbc.queryForObject("select active from app_user where id = ?", Boolean.class, responsible)).isTrue();
		assertActive(link.link().id());
	}

	@Test
	void anUnavailablePlatformKeepsTheChangeAndLogsAWarning() throws Exception {
		UUID responsible = user(manager(), this.storeA);
		ActivatedLink link = link(responsible, this.companyA, "shop-g4b-down", this.storeA);
		doThrow(new IllegalStateException("base plateforme indisponible (test)")).when(this.registry).listForCompany(any(UUID.class));

		asActor(() -> this.users.setActive(responsible, false));

		assertThat(this.jdbc.queryForObject("select active from app_user where id = ?", Boolean.class, responsible))
			.as("changement de droits validé").isFalse();
		assertThat(this.warnings.list).as("WARN avec l'entreprise")
			.anyMatch(event -> event.getLevel() == Level.WARN
					&& event.getFormattedMessage().contains(this.companyA.companyId().toString()));
		org.mockito.Mockito.reset(this.registry);
		assertActive(link.link().id());
	}

	@Test
	void reassessingTwiceGivesTheSameResult() throws Exception {
		UUID responsible = user(manager(), this.storeA);
		ActivatedLink lost = link(responsible, this.companyA, "shop-g4b-twice", this.storeA);
		ActivatedLink kept = link(this.actor, this.companyA, "shop-g4b-twice-kept", this.storeA);
		this.jdbc.update("update app_user set active = false where id = ?", responsible);

		this.watcher.reassess(this.companyA);
		Link first = this.registry.find(lost.link().id()).orElseThrow();
		this.watcher.reassess(this.companyA);
		Link second = this.registry.find(lost.link().id()).orElseThrow();

		assertThat(first.status()).isEqualTo(LinkStatus.SUSPENDED);
		assertThat(first.suspensionReason()).isEqualTo(SuspensionReason.AUTHORIZER_LOST_ACCESS);
		assertThat(second).isEqualTo(first);
		assertActive(kept.link().id());
	}

	@Test
	void anotherManagerReactivatesWithTheirWarehousesAndBecomesResponsible() throws Exception {
		UUID responsible = user(manager(), this.storeA);
		ActivatedLink link = link(responsible, this.companyA, "shop-g4b-reactivate", this.storeA);
		asActor(() -> this.users.setActive(responsible, false));
		assertSuspended(link.link().id());

		UUID other = user(manager(), this.otherStoreA);
		asUser(other, Set.of(this.otherStoreA), () -> TenantContext.callAs(this.companyA, () -> this.consent.reactivate(
				link.link().id(), this.companyA.companyId(), Set.of(this.otherStoreA), new Authorizer(other, "g4b.autre"))));

		Link reactivated = this.registry.find(link.link().id()).orElseThrow();
		assertThat(reactivated.status()).isEqualTo(LinkStatus.ACTIVE);
		assertThat(reactivated.authorizedBy()).isEqualTo(other);
		assertThat(this.jdbc.queryForList("select warehouse_id from integration_link_warehouse where link_id = ?", UUID.class,
				link.link().id())).containsExactly(this.otherStoreA);
		assertThat(chainStatus(link)).isEqualTo(200);
	}

	// --- Scénario : la liaison du responsable, celle d'une autre personne, celle d'une autre entreprise --------

	private final class Scenario {

		private final ActivatedLink mine;

		private final ActivatedLink someoneElses;

		private final ActivatedLink otherCompanys;

		Scenario(ActivatedLink mine, ActivatedLink someoneElses, ActivatedLink otherCompanys) {
			this.mine = mine;
			this.someoneElses = someoneElses;
			this.otherCompanys = otherCompanys;
		}

		void assertOnlyTheResponsiblesLinkIsSuspended() throws Exception {
			assertSuspended(this.mine.link().id());
			assertThat(chainStatus(this.mine)).as("403 LINK_SUSPENDED aussitôt").isEqualTo(403);
			assertActive(this.someoneElses.link().id());
			assertActive(this.otherCompanys.link().id());
		}

	}

	private Scenario scenario(UUID responsible, UUID... linkWarehouses) {
		String suffix = UUID.randomUUID().toString().substring(0, 6);
		ActivatedLink mine = link(responsible, this.companyA, "shop-g4b-" + suffix, linkWarehouses);
		ActivatedLink someoneElses = link(this.actor, this.companyA, "shop-g4b-other-" + suffix, linkWarehouses);
		// Même identifiant de responsable, dans une autre entreprise : jamais touchée par la réévaluation de A.
		ActivatedLink otherCompanys = link(responsible, this.companyB, "shop-g4b-b-" + suffix);
		return new Scenario(mine, someoneElses, otherCompanys);
	}

	private ActivatedLink link(UUID responsible, TenantRef company, String shop, UUID... linkWarehouses) {
		Link pending = this.links.pending(this.client, company.companyId(), shop, SCOPES, new Authorizer(responsible, "g4b.responsable"));
		for (UUID warehouse : linkWarehouses) {
			TenantContext.runAs(company, () -> this.jdbc.update(
					"insert into integration_link_warehouse (link_id, warehouse_id, added_by) values (?, ?, ?)", pending.id(),
					warehouse, responsible));
		}
		return this.registry.activate(pending.id());
	}

	private void assertSuspended(UUID link) {
		Link current = this.registry.find(link).orElseThrow();
		assertThat(current.status()).as("liaison %s", link).isEqualTo(LinkStatus.SUSPENDED);
		assertThat(current.suspensionReason()).isEqualTo(SuspensionReason.AUTHORIZER_LOST_ACCESS);
	}

	private void assertActive(UUID link) {
		assertThat(this.registry.find(link).orElseThrow().status()).as("liaison %s", link).isEqualTo(LinkStatus.ACTIVE);
	}

	/** Statut d'une route de la chaîne d'intégration pour la liaison (jeton obtenu par client_credentials). */
	private int chainStatus(ActivatedLink link) throws Exception {
		String body = this.mvc.perform(post("/api/integration/v1/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.header(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder()
				.encodeToString((this.client.clientId() + ":" + this.clientSecret).getBytes(StandardCharsets.UTF_8)))
			.param("grant_type", "client_credentials").param("link_id", link.link().id().toString())
			.param("link_secret", link.secret()))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String token = this.json.readTree(body).get("access_token").asString();
		var response = this.mvc.perform(get(CATALOG).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn().getResponse();
		if (response.getStatus() == 403) {
			assertThat(this.json.readTree(response.getContentAsString()).get("code").asString()).isEqualTo("LINK_SUSPENDED");
		}
		return response.getStatus();
	}

	// --- Personnes, rôles, droits --------------------------------------------------------------------------------

	/** Rôle de gestion des liaisons sans warehouse:all. */
	private String manager() {
		return role(Permissions.INTEGRATION_MANAGE);
	}

	private String role(String... permissions) {
		String code = "G4B_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
		UUID id = this.jdbc.queryForObject("insert into role (code, label) values (?, 'Essai G4b') returning id", UUID.class, code);
		for (String permission : permissions) {
			this.jdbc.update("insert into role_permission (role_id, permission_id) select ?, id from permission where code = ?", id,
					permission);
		}
		this.createdRoles.add(id);
		return code;
	}

	private UUID roleId(String code) {
		return this.jdbc.queryForObject("select id from role where code = ?", UUID.class, code);
	}

	private UUID user(String roleCode, UUID... assigned) {
		UUID id = this.jdbc.queryForObject(
				"insert into app_user (username, last_name, must_change_password) values (?, 'Essai G4b', false) returning id",
				UUID.class, "g4b." + UUID.randomUUID().toString().substring(0, 8));
		this.jdbc.update("insert into user_role (user_id, role_id) select ?, id from role where code = ?", id, roleCode);
		for (UUID warehouse : assigned) {
			this.jdbc.update("insert into user_warehouse (user_id, warehouse_id) values (?, ?)", id, warehouse);
		}
		this.createdUsers.add(id);
		return id;
	}

	private List<String> roleCodesOf(UUID user) {
		return this.jdbc.queryForList("select r.code from user_role ur join role r on r.id = ur.role_id where ur.user_id = ?",
				String.class, user);
	}

	private UpdateUserRequest update(UUID user, List<String> roleCodes, UUID... assigned) {
		Map<String, Object> row = this.jdbc.queryForMap("select username, last_name from app_user where id = ?", user);
		return new UpdateUserRequest((String) row.get("username"), null, (String) row.get("last_name"), null, null, null,
				roleCodes, null, List.of(assigned));
	}

	private <T> T asActor(java.util.function.Supplier<T> action) {
		return as(StockFixtures.authentication(this.actor, Permissions.ALL.toArray(String[]::new)), action);
	}

	/** Personne connectée comme par un jeton : principal {@link CurrentUser} porteur de ses dépôts affectés. */
	private <T> T asUser(UUID user, Set<UUID> assigned, java.util.function.Supplier<T> action) {
		CurrentUser principal = new CurrentUser(user, "g4b.autre", Set.of(), Set.of(Permissions.INTEGRATION_MANAGE), null,
				assigned);
		Jwt token = Jwt.withTokenValue("test").header("alg", "HS256").subject(user.toString()).build();
		return as(new CurrentUserAuthentication(principal, token,
				List.of(new SimpleGrantedAuthority(Permissions.INTEGRATION_MANAGE))), action);
	}

	private static <T> T as(org.springframework.security.core.Authentication authentication, java.util.function.Supplier<T> action) {
		SecurityContext previous = SecurityContextHolder.getContext();
		try {
			SecurityContext context = SecurityContextHolder.createEmptyContext();
			context.setAuthentication(authentication);
			SecurityContextHolder.setContext(context);
			return action.get();
		}
		finally {
			SecurityContextHolder.setContext(previous);
		}
	}

	// --- Entreprise B ----------------------------------------------------------------------------------------------

	private TenantRef provisionCompanyB() throws Exception {
		this.platform.jdbc().sql("delete from platform.platform_admin where username = :u").param("u", SUPER_ADMIN).update();
		this.platform.jdbc()
			.sql("insert into platform.platform_admin (username, full_name, password_hash, must_change_password) values (:u, 'IT', :h, false)")
			.param("u", SUPER_ADMIN).param("h", this.passwordEncoder.encode(SUPER_ADMIN_PASSWORD)).update();
		String login = this.mvc.perform(post("/api/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\": \"" + SUPER_ADMIN + "\", \"password\": \"" + SUPER_ADMIN_PASSWORD + "\"}"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String superToken = this.json.readTree(login).get("accessToken").asString();
		String code = "it-g4b-" + UUID.randomUUID().toString().substring(0, 8);
		String created = this.mvc.perform(post("/api/platform/companies").header(HttpHeaders.AUTHORIZATION, "Bearer " + superToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"code": "%s", "legalName": "Commerce B SARL", "tradeName": "Commerce B", "ownerLastName": "Kouassi",
					 "adminUsername": "admin"}""".formatted(code)))
			.andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ACTIVE"))
			.andReturn().getResponse().getContentAsString();
		UUID id = UUID.fromString(this.json.readTree(created).get("id").asString());
		this.createdCompanies.add(id);
		return new TenantRef(id, code);
	}

	private void dropCompanyB() throws Exception {
		for (UUID id : this.createdCompanies) {
			this.pools.close(id);
			var database = this.platform.jdbc()
				.sql("select database_name, runtime_role from platform.company_database where company_id = :id and mode = 'DEDICATED'")
				.param("id", id).query((rs, n) -> new String[] { rs.getString(1), rs.getString(2) }).optional();
			if (database.isPresent()) {
				try (Connection c = this.provisioner.server().getConnection(); Statement statement = c.createStatement()) {
					statement.execute("DROP DATABASE IF EXISTS \"" + database.get()[0] + "\" WITH (FORCE)");
					statement.execute("DROP ROLE IF EXISTS \"" + database.get()[1] + "\"");
				}
			}
			this.platform.inTransaction(() -> {
				for (String sql : List.of("delete from platform.provisioning_job where company_id = :id",
						"delete from platform.company_database where company_id = :id",
						"delete from platform.platform_audit_log where company_id = :id",
						"delete from platform.company where id = :id")) {
					this.platform.jdbc().sql(sql).param("id", id).update();
				}
			});
		}
		this.platform.jdbc().sql("delete from platform.platform_admin where username = :u").param("u", SUPER_ADMIN).update();
	}

	/** Route d'intégration de test : effet immédiat de la suspension sur la chaîne d'intégration (G7). */
	@RestController
	@RequestMapping("/api/integration/v1/test-lost-access")
	static class LostAccessTestController {

		@GetMapping("/catalog")
		@RequiresScope("catalog:read")
		Map<String, Object> catalog() {
			return Map.of("ok", true);
		}

	}

}
