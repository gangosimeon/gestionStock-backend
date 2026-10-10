package com.geststock.audit;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.geststock.support.PostgresTestConfiguration;
import com.geststock.support.WithPermissionsSecurityContextFactory;
import com.geststock.user.UserService;
import com.geststock.user.dto.CreateUserRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Connexions et déconnexions tracées, avec de vrais jetons et en transactions réelles :
 * l'auteur est l'identité vérifiée par le serveur ; un échec de connexion n'a pas d'auteur
 * (le nom saisi n'est qu'un libellé) ; un appel muni d'un jeton est attribué à son titulaire.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class AuthAuditIT {

	private static final String PASSWORD = "Optique2026";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private UserService users;

	@Autowired
	private JsonMapper json;

	private final String username = "audauth_" + UUID.randomUUID().toString().substring(0, 8);

	@AfterEach
	void deleteTestUser() {
		this.jdbc.update("delete from app_user where username = ?", this.username);
	}

	@Test
	void loginLogoutAndRefusalsAreTracedWithAServerSideIdentity() throws Exception {
		UUID id = WithPermissionsSecurityContextFactory.runWithAllPermissions(() -> this.users.create(
				new CreateUserRequest(this.username, null, "Audit", null, null, null, PASSWORD, List.of("CAISSIER"), null,
						null, null)).id());

		login(this.username, "mauvais").andExpect(status().isUnauthorized());
		JsonNode session = this.json.readTree(login(this.username, PASSWORD).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());
		String accessToken = session.get("accessToken").asString();

		// Appel authentifié : l'en-tête X-User-Id envoyé par le client est ignoré.
		this.mvc.perform(post("/api/auth/change-password").header("Authorization", "Bearer " + accessToken)
			.header("X-User-Id", UUID.randomUUID().toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"currentPassword\": \"faux\", \"newPassword\": \"Optique2027\"}"))
			.andExpect(status().isBadRequest());
		this.mvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
			.content("{\"refreshToken\": \"%s\"}".formatted(session.get("refreshToken").asString())))
			.andExpect(status().isNoContent());

		List<Map<String, Object>> entries = this.jdbc.queryForList("""
				select action, status, module, user_id, username, user_role, entity_label, meta ->> 'errorCode' as code,
				       ip_address
				from audit_log
				where entity_type = 'USER' and (entity_id = ? or entity_label = ? or user_id = ?) and action <> 'CREATE'
				order by created_at""", id.toString(), this.username, id);

		assertThat(entries).extracting(e -> e.get("action") + "/" + e.get("status"))
			.containsExactly("LOGIN/FAILURE", "LOGIN/SUCCESS", "PASSWORD_CHANGE/FAILURE", "LOGOUT/SUCCESS");
		assertThat(entries).allSatisfy(e -> assertThat(e.get("module")).isEqualTo("AUTH"));

		Map<String, Object> refused = entries.get(0);
		assertThat(refused.get("user_id")).as("aucun auteur pour un échec de connexion").isNull();
		assertThat(refused.get("username")).isNull();
		assertThat(refused.get("entity_label")).isEqualTo(this.username);
		assertThat(refused.get("code")).isEqualTo("INVALID_CREDENTIALS");

		Map<String, Object> loggedIn = entries.get(1);
		assertThat(loggedIn.get("user_id")).isEqualTo(id);
		assertThat(loggedIn.get("username")).isEqualTo(this.username);
		assertThat(loggedIn.get("user_role")).isEqualTo("CAISSIER");
		assertThat(loggedIn.get("ip_address")).isEqualTo("127.0.0.1");

		Map<String, Object> wrongPassword = entries.get(2);
		assertThat(wrongPassword.get("user_id")).as("titulaire du jeton").isEqualTo(id);
		assertThat(wrongPassword.get("code")).isEqualTo("CURRENT_PASSWORD_INVALID");

		assertThat(entries.get(3).get("user_id")).as("propriétaire du jeton révoqué").isEqualTo(id);
	}

	@Test
	void anUnknownUserFailsWithoutAnAuthor() throws Exception {
		login(this.username + "_inconnu", PASSWORD).andExpect(status().isUnauthorized());

		Map<String, Object> entry = this.jdbc.queryForMap("""
				select status, user_id, entity_label, meta ->> 'httpStatus' as http from audit_log
				where action = 'LOGIN' and entity_label = ?""", this.username + "_inconnu");
		assertThat(entry.get("status")).isEqualTo("FAILURE");
		assertThat(entry.get("user_id")).isNull();
		assertThat(entry.get("http")).isEqualTo("401");
	}

	private ResultActions login(String user, String password) throws Exception {
		return this.mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\": \"" + user + "\", \"password\": \"" + password + "\"}"));
	}

}
