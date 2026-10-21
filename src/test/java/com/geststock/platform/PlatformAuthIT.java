package com.geststock.platform;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.geststock.support.PostgresTestConfiguration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Super Admin : mot de passe initial à changer avant tout accès, politique renforcée, verrouillage,
 * rotation des sessions et détection de réutilisation, journal de la plateforme.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class PlatformAuthIT {

	private static final String USERNAME = "it_platform_auth";

	private static final String INITIAL = "Initial-Pass-2026";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private PlatformDb platform;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JsonMapper json;

	@BeforeEach
	void createAdmin() {
		deleteAdmin();
		this.platform.jdbc()
			.sql("insert into platform.platform_admin (username, full_name, password_hash, must_change_password) values (:u, 'IT', :h, true)")
			.param("u", USERNAME)
			.param("h", this.passwordEncoder.encode(INITIAL))
			.update();
	}

	@AfterEach
	void deleteAdmin() {
		this.platform.jdbc().sql("delete from platform.platform_audit_log where actor_name = :u").param("u", USERNAME).update();
		this.platform.jdbc().sql("delete from platform.platform_admin where username = :u").param("u", USERNAME).update();
	}

	@Test
	void theInitialPasswordMustBeChangedBeforeAnyAdministration() throws Exception {
		JsonNode session = read(login(INITIAL).andExpect(status().isOk()).andExpect(jsonPath("$.admin.mustChangePassword").value(true)));
		String token = session.get("accessToken").asString();
		this.mvc.perform(get("/api/platform/companies").with(bearer(token))).andExpect(status().isForbidden());
		this.mvc.perform(get("/api/platform/auth/me").with(bearer(token))).andExpect(status().isOk());

		changePassword(token, INITIAL, "trop-court").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PASSWORD_POLICY_VIOLATION"));
		JsonNode renewed = read(changePassword(token, INITIAL, "Nouveau-Plateforme-2026").andExpect(status().isOk())
			.andExpect(jsonPath("$.admin.mustChangePassword").value(false)));
		this.mvc.perform(get("/api/platform/companies").with(bearer(renewed.get("accessToken").asString())))
			.andExpect(status().isOk());
		// Ancienne session fermée.
		refresh(session.get("refreshToken").asString()).andExpect(status().isUnauthorized());
		assertThat(this.platform.jdbc()
			.sql("select count(*) from platform.platform_audit_log where actor_name = :u and action = 'PLATFORM_PASSWORD_CHANGE'")
			.param("u", USERNAME)
			.query(Integer.class)
			.single()).isEqualTo(1);
	}

	@Test
	void repeatedFailuresLockTheAccount() throws Exception {
		for (int i = 0; i < 3; i++) {
			login("Mauvais-Pass-2026").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
		}
		login(INITIAL).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
		this.mvc.perform(post("/api/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\": \"inconnu_xyz\", \"password\": \"x\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
	}

	@Test
	void aReusedRefreshTokenClosesTheWholeSession() throws Exception {
		JsonNode session = read(login(INITIAL).andExpect(status().isOk()));
		String first = session.get("refreshToken").asString();
		String second = read(refresh(first).andExpect(status().isOk())).get("refreshToken").asString();
		refresh(first).andExpect(status().isUnauthorized());
		refresh(second).andExpect(status().isUnauthorized());
	}

	private ResultActions login(String password) throws Exception {
		return this.mvc.perform(post("/api/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content(this.json.writeValueAsString(Map.of("username", USERNAME, "password", password))));
	}

	private ResultActions refresh(String token) throws Exception {
		return this.mvc.perform(post("/api/platform/auth/refresh").contentType(MediaType.APPLICATION_JSON)
			.content("{\"refreshToken\": \"" + token + "\"}"));
	}

	private ResultActions changePassword(String token, String current, String next) throws Exception {
		return this.mvc.perform(post("/api/platform/auth/change-password").with(bearer(token)).contentType(MediaType.APPLICATION_JSON)
			.content(this.json.writeValueAsString(Map.of("currentPassword", current, "newPassword", next))));
	}

	private static RequestPostProcessor bearer(String token) {
		return request -> {
			request.addHeader("Authorization", "Bearer " + token);
			return request;
		};
	}

	private JsonNode read(ResultActions result) throws Exception {
		return this.json.readTree(result.andReturn().getResponse().getContentAsString());
	}

}
