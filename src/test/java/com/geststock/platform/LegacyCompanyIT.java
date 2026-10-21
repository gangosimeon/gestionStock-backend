package com.geststock.platform;

import java.util.UUID;

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

import com.geststock.support.PostgresTestConfiguration;

import tools.jackson.databind.json.JsonMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Base historique rattachée comme entreprise (mode LEGACY, sans provisionnement) : fiche consultable. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class LegacyCompanyIT {

	private static final String USERNAME = "it_legacy_admin";

	private static final String PASSWORD = "Legacy-Test-2026";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private PlatformDb platform;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JsonMapper json;

	private String token;

	@BeforeEach
	void login() throws Exception {
		deleteAdmin();
		this.platform.jdbc()
			.sql("insert into platform.platform_admin (username, full_name, password_hash, must_change_password) values (:u, 'IT', :h, false)")
			.param("u", USERNAME)
			.param("h", this.passwordEncoder.encode(PASSWORD))
			.update();
		this.token = this.json.readTree(this.mvc.perform(post("/api/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\": \"" + USERNAME + "\", \"password\": \"" + PASSWORD + "\"}"))
			.andReturn().getResponse().getContentAsString()).get("accessToken").asString();
	}

	@AfterEach
	void deleteAdmin() {
		this.platform.jdbc().sql("delete from platform.platform_audit_log where actor_name = :u").param("u", USERNAME).update();
		this.platform.jdbc().sql("delete from platform.platform_admin where username = :u").param("u", USERNAME).update();
	}

	@Test
	void theLegacyCompanyDetailAndBrandingLoad() throws Exception {
		UUID id = this.platform.jdbc().sql("select id from platform.company where code = 'test'").query(UUID.class).single();
		this.mvc.perform(get("/api/platform/companies/{id}", id).header("Authorization", "Bearer " + this.token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value("test"))
			.andExpect(jsonPath("$.database.mode").value("LEGACY"));
		this.mvc.perform(get("/api/platform/companies/{id}/branding", id).header("Authorization", "Bearer " + this.token))
			.andExpect(status().isOk());
		this.mvc.perform(get("/api/platform/audit-log").param("companyId", id.toString()).header("Authorization", "Bearer " + this.token))
			.andExpect(status().isOk());
		this.mvc.perform(get("/api/platform/stats").header("Authorization", "Bearer " + this.token)).andExpect(status().isOk());
		this.mvc.perform(get("/api/platform/companies").header("Authorization", "Bearer " + this.token)).andExpect(status().isOk());
	}

}
