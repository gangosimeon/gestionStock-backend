package com.geststock.security;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.json.JsonMapper;

import com.geststock.support.PostgresTestConfiguration;
import com.geststock.support.WithPermissionsSecurityContextFactory;
import com.geststock.user.UserService;
import com.geststock.user.dto.CreateUserRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Matrice des droits des rôles livrés, de bout en bout : vrais utilisateurs, vraie connexion,
 * vrais jetons JWT, rôles et permissions relus en base. Chaque ligne énonce une règle de
 * conception des rôles (« un caissier vend mais n'annule pas »…) ; un refus doit être un 403,
 * un accès autorisé ne doit jamais l'être (la requête peut ensuite échouer pour une raison métier,
 * par exemple sur un identifiant inexistant).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class RolePermissionMatrixIT {

	private static final String PASSWORD = "Optique2026!";

	private static final Set<String> ALL = Set.of("ADMIN", "GESTIONNAIRE", "CAISSIER");

	private static final Set<String> MANAGERS = Set.of("ADMIN", "GESTIONNAIRE");

	private static final Set<String> ADMIN = Set.of("ADMIN");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private UserService users;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private JsonMapper json;

	private final String suffix = UUID.randomUUID().toString().substring(0, 6);

	private final Map<String, String> tokens = new HashMap<>();

	private UUID customer;

	@BeforeEach
	void logInEachRole() throws Exception {
		for (String role : ALL) {
			String username = "matrix_" + role.toLowerCase() + "_" + this.suffix;
			WithPermissionsSecurityContextFactory.runWithAllPermissions(() -> this.users.create(new CreateUserRequest(
					username, null, "Matrice " + role, null, null, null, PASSWORD, List.of(role), null, null, null)));
			MvcResult login = this.mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\": \"%s\", \"password\": \"%s\"}".formatted(username, PASSWORD))).andReturn();
			assertThat(login.getResponse().getStatus()).as("connexion " + role).isEqualTo(200);
			this.tokens.put(role,
					this.json.readTree(login.getResponse().getContentAsString()).get("accessToken").asString());
		}
		this.customer = this.jdbc.queryForObject(
				"insert into customer (code, name, credit_limit) values (?, 'Client matrice', 50000) returning id",
				UUID.class, "CLI-MX-" + this.suffix.toUpperCase());
	}

	@AfterEach
	void deleteUsers() {
		this.jdbc.update("delete from app_user where username like ?", "matrix\\_%\\_" + this.suffix);
		this.jdbc.update("delete from customer where id = ?", this.customer);
	}

	@Test
	void builtInRolesGrantExactlyWhatTheirJobNeeds() throws Exception {
		UUID random = UUID.randomUUID();
		String sale = "{\"paymentMethod\": \"CASH\", \"items\": [{\"productId\": \"%s\", \"quantity\": 1}]}".formatted(random);
		String giveBack = """
				{"settlement": "REFUND", "reason": "x", "items": [{"productId": "%s", "quantity": 1, "condition": "RESELLABLE"}]}"""
			.formatted(random);
		List<Case> cases = List.of(
				// Vente et encaissement : le métier du caissier.
				new Case(HttpMethod.GET, "/api/products", null, ALL),
				new Case(HttpMethod.POST, "/api/sales", sale, ALL),
				new Case(HttpMethod.GET, "/api/cash-register/current", null, ALL),
				new Case(HttpMethod.POST, "/api/customers/" + random + "/payments", "{\"amount\": 1000}", ALL),
				new Case(HttpMethod.PUT, "/api/customers/" + this.customer,
						"{\"name\": \"Client matrice modifié\", \"creditLimit\": 50000}", ALL),
				new Case(HttpMethod.GET, "/api/alerts", null, ALL),
				// Corrections commerciales et stock : responsable seulement.
				new Case(HttpMethod.POST, "/api/sales/" + random + "/cancel", "{\"reason\": \"Erreur\"}", MANAGERS),
				new Case(HttpMethod.POST, "/api/sales/" + random + "/returns", giveBack, MANAGERS),
				new Case(HttpMethod.POST, "/api/stock/movements",
						"{\"productId\": \"%s\", \"quantity\": -1, \"reason\": \"LOSS\"}".formatted(random), MANAGERS),
				new Case(HttpMethod.POST, "/api/warehouses/transfer", """
						{"fromWarehouseId": "%s", "toWarehouseId": "%s", "productId": "%s", "quantity": 1}"""
					.formatted(random, UUID.randomUUID(), random), MANAGERS),
				new Case(HttpMethod.POST, "/api/purchases/orders", """
						{"supplierId": "%s", "lines": [{"productId": "%s", "quantity": 1}]}""".formatted(random, random),
						MANAGERS),
				new Case(HttpMethod.POST, "/api/purchases/orders/" + random + "/receive", "{}", MANAGERS),
				new Case(HttpMethod.GET, "/api/inventories", null, MANAGERS),
				new Case(HttpMethod.POST, "/api/inventories/" + random + "/validate", null, MANAGERS),
				new Case(HttpMethod.GET, "/api/reports/sales", null, MANAGERS),
				new Case(HttpMethod.GET, "/api/dashboard", null, MANAGERS),
				// Devis (V036) : propositions commerciales établies par le responsable.
				new Case(HttpMethod.GET, "/api/quotes", null, MANAGERS),
				new Case(HttpMethod.POST, "/api/quotes", "{}", MANAGERS),
				// Plafond de crédit : donnée sensible, refusée au caissier même s'il modifie le client.
				new Case(HttpMethod.PUT, "/api/customers/" + this.customer,
						"{\"name\": \"Client matrice\", \"creditLimit\": 80000}", MANAGERS),
				// Vue de tous les dépôts et administration des dépôts : administrateur seulement (V029).
				new Case(HttpMethod.GET, "/api/sales?allWarehouses=true", null, ADMIN),
				new Case(HttpMethod.GET, "/api/dashboard?allWarehouses=true", null, ADMIN),
				new Case(HttpMethod.POST, "/api/warehouses", "{}", ADMIN),
				new Case(HttpMethod.PATCH, "/api/warehouses/" + random + "/active", "{\"active\": true}", ADMIN),
				// Administration : administrateur seulement.
				new Case(HttpMethod.GET, "/api/users", null, ADMIN),
				new Case(HttpMethod.GET, "/api/roles", null, ADMIN),
				new Case(HttpMethod.GET, "/api/audit-logs", null, ADMIN),
				new Case(HttpMethod.DELETE, "/api/products/" + random, null, ADMIN));

		for (Case rule : cases) {
			for (String role : ALL) {
				// État identique pour chaque rôle : le plafond vaut toujours 50 000 avant l'appel, la
				// demande à 80 000 est donc toujours un vrai changement de plafond.
				this.jdbc.update("update customer set credit_limit = 50000 where id = ?", this.customer);
				int status = perform(role, rule);
				if (rule.allowed().contains(role)) {
					assertThat(status).as("%s doit pouvoir %s %s", role, rule.method(), rule.path())
						.isNotIn(401, 403);
				}
				else {
					assertThat(status).as("%s ne doit pas pouvoir %s %s", role, rule.method(), rule.path())
						.isEqualTo(403);
				}
			}
		}
	}

	@Test
	void alertThresholdsAreConfiguredByManagersOnly() throws Exception {
		String current = this.mvc.perform(MockMvcRequestBuilders.get("/api/alerts/settings")
			.header("Authorization", "Bearer " + this.tokens.get("ADMIN"))).andReturn().getResponse().getContentAsString();
		// Mêmes valeurs renvoyées : aucune modification réelle, seul le droit est en jeu.
		Case update = new Case(HttpMethod.PUT, "/api/alerts/settings", current, MANAGERS);
		assertThat(perform("CAISSIER", update)).isEqualTo(403);
		assertThat(perform("GESTIONNAIRE", update)).isEqualTo(200);
	}

	@Test
	void invoiceSettingsAreReadBySellersAndConfiguredByTheAdministratorOnly() throws Exception {
		Case read = new Case(HttpMethod.GET, "/api/settings/invoice", null, ALL);
		for (String role : ALL) {
			assertThat(perform(role, read)).as("lecture par " + role).isEqualTo(200);
		}
		String current = this.mvc.perform(MockMvcRequestBuilders.get("/api/settings/invoice")
			.header("Authorization", "Bearer " + this.tokens.get("ADMIN"))).andReturn().getResponse().getContentAsString();
		// Mêmes valeurs renvoyées : aucune modification réelle, seul le droit est en jeu.
		Case update = new Case(HttpMethod.PUT, "/api/settings/invoice", current, ADMIN);
		assertThat(perform("CAISSIER", update)).isEqualTo(403);
		assertThat(perform("GESTIONNAIRE", update)).isEqualTo(403);
		assertThat(perform("ADMIN", update)).isEqualTo(200);
		Case reset = new Case(HttpMethod.POST, "/api/settings/invoice/reset", null, ADMIN);
		Case removeLogo = new Case(HttpMethod.DELETE, "/api/settings/invoice/logo", null, ADMIN);
		for (String role : List.of("CAISSIER", "GESTIONNAIRE")) {
			assertThat(perform(role, reset)).as("réinitialisation par " + role).isEqualTo(403);
			assertThat(perform(role, removeLogo)).as("retrait du logo par " + role).isEqualTo(403);
		}
	}

	private int perform(String role, Case rule) throws Exception {
		MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(rule.method(), rule.path())
			.header("Authorization", "Bearer " + this.tokens.get(role));
		if (rule.body() != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(rule.body());
		}
		return this.mvc.perform(request).andReturn().getResponse().getStatus();
	}

	private record Case(HttpMethod method, String path, String body, Set<String> allowed) {
	}

}
