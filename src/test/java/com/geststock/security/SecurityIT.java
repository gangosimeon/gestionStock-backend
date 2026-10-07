package com.geststock.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.geststock.role.RoleService;
import com.geststock.role.dto.CreateRoleRequest;
import com.geststock.role.dto.UpdateRoleRequest;
import com.geststock.support.PostgresTestConfiguration;
import com.geststock.support.WithPermissionsSecurityContextFactory;
import com.geststock.user.UserService;
import com.geststock.user.dto.CreateUserRequest;
import com.geststock.user.dto.ResetPasswordRequest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sécurité de bout en bout avec de vrais jetons, sur PostgreSQL : connexion, validation
 * des JWT, droits calculés par le serveur, révocation des sessions.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class SecurityIT {

	private static final String PASSWORD = "Optique2026";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private UserService users;

	@Autowired
	private JwtEncoder jwtEncoder;

	@Autowired
	private RoleService roles;

	@Autowired
	private JsonMapper json;

	@AfterEach
	void deleteTestUsers() {
		// Les refresh tokens et rôles suivent par ON DELETE CASCADE.
		this.jdbc.update("delete from app_user where username like 'sec\\_%'");
	}

	// --- Connexion ----------------------------------------------------------------------

	@Test
	void loginReturnsTokensAndTheFrontContract() throws Exception {
		createUser("sec_caisse", "CAISSIER");

		login("sec_caisse", PASSWORD)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken").isString())
			.andExpect(jsonPath("$.refreshToken").isString())
			.andExpect(jsonPath("$.expiresIn").value(900))
			.andExpect(jsonPath("$.tokenType").value("Bearer"))
			.andExpect(jsonPath("$.user.username").value("sec_caisse"))
			.andExpect(jsonPath("$.user.role").value("EMPLOYEE"))
			.andExpect(jsonPath("$.user.roles[0]").value("CAISSIER"))
			.andExpect(jsonPath("$.user.permissions", hasItem("sale:create")))
			.andExpect(jsonPath("$.user.permissions", not(hasItem("stock:adjust"))))
			.andExpect(content().string(not(containsString("passwordHash"))))
			.andExpect(content().string(not(containsString(PASSWORD))));
	}

	@Test
	void unknownUserAndWrongPasswordGetTheSameAnswer() throws Exception {
		createUser("sec_existe", "CAISSIER");

		String unknown = login("sec_inconnu", PASSWORD).andExpect(status().isUnauthorized())
			.andReturn().getResponse().getContentAsString();
		String wrong = login("sec_existe", "Mauvais2026").andExpect(status().isUnauthorized())
			.andReturn().getResponse().getContentAsString();

		assertThat(read(unknown).get("code").asString()).isEqualTo("INVALID_CREDENTIALS");
		assertThat(read(wrong).get("code").asString()).isEqualTo("INVALID_CREDENTIALS");
		assertThat(read(unknown).get("message").asString()).isEqualTo(read(wrong).get("message").asString());
	}

	@Test
	void accountIsLockedAfterRepeatedFailures() throws Exception {
		createUser("sec_lock", "CAISSIER");
		for (int i = 0; i < 3; i++) {
			login("sec_lock", "Mauvais2026").andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
		}

		login("sec_lock", PASSWORD)
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
	}

	@Test
	void disabledAccountCannotLogInAndOnlyLearnsItWithTheRightPassword() throws Exception {
		UUID id = createUser("sec_off", "CAISSIER");
		this.jdbc.update("update app_user set active = false where id = ?", id);

		login("sec_off", "Mauvais2026").andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
		login("sec_off", PASSWORD).andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
	}

	@Test
	void accountWithoutPasswordCannotLogIn() throws Exception {
		WithPermissionsSecurityContextFactory.runWithAllPermissions(() -> this.users.create(new CreateUserRequest(
				"sec_nopwd", null, "Sans", null, null, null, null, List.of("CAISSIER"), null, null, null)));

		login("sec_nopwd", "").andExpect(status().isBadRequest());
		login("sec_nopwd", "nimporte1").andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
	}

	// --- Validation des jetons -----------------------------------------------------------

	@Test
	void protectedEndpointsRequireAToken() throws Exception {
		this.mvc.perform(get("/api/users"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
		this.mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
	}

	@Test
	void meReturnsServerComputedPermissions() throws Exception {
		createUser("sec_me", "GESTIONNAIRE");
		String token = accessToken("sec_me");

		this.mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.username").value("sec_me"))
			.andExpect(jsonPath("$.role").value("MANAGER"))
			.andExpect(jsonPath("$.permissions", hasItem("stock:adjust")))
			.andExpect(jsonPath("$.permissions", not(hasItem("user:manage"))));
	}

	@Test
	void malformedTamperedUnsignedAndExpiredTokensAreRejected() throws Exception {
		UUID id = createUser("sec_tok", "ADMIN");
		String valid = accessToken("sec_tok");

		expectInvalidToken("pas-un-jwt");

		String[] parts = valid.split("\\.");
		String forgedPayload = base64Url(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
			.replace("sec_tok", "sec_xxx"));
		expectInvalidToken(parts[0] + "." + forgedPayload + "." + parts[2]);

		String unsignedHeader = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
		expectInvalidToken(unsignedHeader + "." + parts[1] + ".");

		Instant past = Instant.now().minusSeconds(3600);
		expectInvalidToken(sign(id, past, past.plusSeconds(600), List.of("ADMIN")));
	}

	@Test
	void permissionsComeFromTheDatabaseNotFromTheToken() throws Exception {
		UUID cashier = createUser("sec_cash", "CAISSIER");

		// Jeton correctement signé mais dont la revendication « roles » prétend ADMIN.
		String forgedRoles = sign(cashier, Instant.now(), Instant.now().plusSeconds(600), List.of("ADMIN"));

		this.mvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + forgedRoles))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
	}

	@Test
	void adminCanManageUsersWhileCashierCannot() throws Exception {
		createUser("sec_admin", "ADMIN");
		createUser("sec_cashier", "CAISSIER");

		this.mvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken("sec_admin")))
			.andExpect(status().isOk());
		this.mvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken("sec_cashier")))
			.andExpect(status().isForbidden());
	}

	@Test
	void removingAPermissionFromARoleAppliesToOpenSessionsImmediately() throws Exception {
		WithPermissionsSecurityContextFactory.runWithAllPermissions(() -> this.roles.create(new CreateRoleRequest(
				"SEC_GESTION_COMPTES", "Gestion des comptes", null, List.of("user:manage"))));
		try {
			createUser("sec_rh", "SEC_GESTION_COMPTES");
			String token = accessToken("sec_rh");
			this.mvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk());

			UUID roleId = this.jdbc.queryForObject("select id from role where code = 'SEC_GESTION_COMPTES'", UUID.class);
			WithPermissionsSecurityContextFactory.runWithAllPermissions(() -> this.roles.update(roleId,
					new UpdateRoleRequest("Gestion des comptes", null, List.of("customer:read"))));

			// Même jeton, droits relus en base : l'accès est retiré sans reconnexion.
			this.mvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isForbidden());
		}
		finally {
			this.jdbc.update("delete from app_user where username like 'sec\\_%'");
			this.jdbc.update("delete from role_permission where role_id = (select id from role where code = 'SEC_GESTION_COMPTES')");
			this.jdbc.update("delete from role where code = 'SEC_GESTION_COMPTES'");
			this.roles.evictPermissionCache();
		}
	}

	@Test
	void userWithoutRoleGetsNoPermissionAtAll() throws Exception {
		UUID id = createUser("sec_norole", "CAISSIER");
		this.jdbc.update("delete from user_role where user_id = ?", id);
		String token = accessToken("sec_norole");

		this.mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.permissions").isEmpty());
		this.mvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isForbidden());
	}

	@Test
	void deactivationRevokesExistingSessionsImmediately() throws Exception {
		UUID id = createUser("sec_kick", "CAISSIER");
		JsonNode session = read(login("sec_kick", PASSWORD).andReturn().getResponse().getContentAsString());

		this.users.setActive(id, false);

		this.mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + session.get("accessToken").asString()))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
		refresh(session.get("refreshToken").asString()).andExpect(status().isUnauthorized());
	}

	@Test
	void passwordResetInvalidatesTokensIssuedBefore() throws Exception {
		UUID id = createUser("sec_reset", "CAISSIER");
		// Compte dont le mot de passe date d'une heure : un jeton émis il y a 10 s est valide.
		this.jdbc.update("update app_user set credentials_changed_at = now() - interval '1 hour' where id = ?", id);
		String earlierToken = sign(id, Instant.now().minusSeconds(10), Instant.now().plusSeconds(600), List.of());
		this.mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + earlierToken))
			.andExpect(status().isOk());

		this.users.resetPassword(id, new ResetPasswordRequest("Nouveau2026", true));

		this.mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + earlierToken))
			.andExpect(status().isUnauthorized());
		login("sec_reset", PASSWORD).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
		login("sec_reset", "Nouveau2026").andExpect(status().isOk());
	}

	// --- Sessions : renouvellement, réutilisation, déconnexion -------------------------

	@Test
	void refreshRotatesTokensEvenWhenAnExpiredAccessTokenIsSent() throws Exception {
		createUser("sec_rot", "CAISSIER");
		JsonNode first = read(login("sec_rot", PASSWORD).andReturn().getResponse().getContentAsString());

		// Le front joint son ancien jeton (souvent expiré) à la requête de renouvellement.
		String body = this.mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
			.header(HttpHeaders.AUTHORIZATION, "Bearer jeton-expire")
			.content("{\"refreshToken\": \"" + first.get("refreshToken").asString() + "\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.user.username").value("sec_rot"))
			.andReturn().getResponse().getContentAsString();

		assertThat(read(body).get("refreshToken").asString()).isNotEqualTo(first.get("refreshToken").asString());
	}

	@Test
	void reusingARotatedRefreshTokenRevokesTheWholeSession() throws Exception {
		createUser("sec_reuse", "CAISSIER");
		String original = read(login("sec_reuse", PASSWORD).andReturn().getResponse().getContentAsString())
			.get("refreshToken").asString();
		String rotated = read(refresh(original).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
			.get("refreshToken").asString();

		refresh(original).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
		// Le jeton légitime (issu de la rotation) est révoqué lui aussi.
		refresh(rotated).andExpect(status().isUnauthorized());
	}

	@Test
	void logoutRevokesTheRefreshTokenAndIsIdempotent() throws Exception {
		createUser("sec_out", "CAISSIER");
		String refreshToken = read(login("sec_out", PASSWORD).andReturn().getResponse().getContentAsString())
			.get("refreshToken").asString();

		logout(refreshToken).andExpect(status().isNoContent());
		logout(refreshToken).andExpect(status().isNoContent());
		logout("inconnu").andExpect(status().isNoContent());
		refresh(refreshToken).andExpect(status().isUnauthorized());
	}

	@Test
	void changingOwnPasswordClosesOtherSessions() throws Exception {
		createUser("sec_chg", "CAISSIER");
		JsonNode session = read(login("sec_chg", PASSWORD).andReturn().getResponse().getContentAsString());
		String auth = "Bearer " + session.get("accessToken").asString();

		this.mvc.perform(post("/api/auth/change-password").header(HttpHeaders.AUTHORIZATION, auth)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"currentPassword\": \"Faux2026x\", \"newPassword\": \"Nouveau2026\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_INVALID"));

		this.mvc.perform(post("/api/auth/change-password").header(HttpHeaders.AUTHORIZATION, auth)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"currentPassword\": \"" + PASSWORD + "\", \"newPassword\": \"Nouveau2026\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.user.mustChangePassword").value(false));

		refresh(session.get("refreshToken").asString()).andExpect(status().isUnauthorized());
		login("sec_chg", "Nouveau2026").andExpect(status().isOk());
	}

	@Test
	void forgotPasswordAnswersTheSameWhetherTheAccountExistsOrNot() throws Exception {
		String unknown = this.mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\": \"personne@exemple.ci\"}"))
			.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
		assertThat(unknown).contains("Si un compte correspond");
	}

	// --- Outils ---------------------------------------------------------------------------

	private UUID createUser(String username, String role) {
		return WithPermissionsSecurityContextFactory.runWithAllPermissions(() -> this.users.create(new CreateUserRequest(
				username, null, "Test", null, null, null, PASSWORD, List.of(role), null, null, null)).id());
	}

	private ResultActions login(String username, String password) throws Exception {
		return this.mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\": \"" + username + "\", \"password\": \"" + password + "\"}"));
	}

	private ResultActions refresh(String refreshToken) throws Exception {
		return this.mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
			.content("{\"refreshToken\": \"" + refreshToken + "\"}"));
	}

	private ResultActions logout(String refreshToken) throws Exception {
		return this.mvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
			.content("{\"refreshToken\": \"" + refreshToken + "\"}"));
	}

	private String accessToken(String username) throws Exception {
		return read(login(username, PASSWORD).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
			.get("accessToken").asString();
	}

	/** Jeton signé avec la vraie clé du serveur, mais au contenu choisi par le test. */
	private String sign(UUID subject, Instant issuedAt, Instant expiresAt, List<String> roles) {
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer("geststock")
			.subject(subject.toString())
			.issuedAt(issuedAt)
			.expiresAt(expiresAt)
			.claim("roles", roles)
			.build();
		return this.jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
			.getTokenValue();
	}

	private void expectInvalidToken(String token) throws Exception {
		this.mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_TOKEN"))
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("invalid_token")));
	}

	private JsonNode read(String body) {
		return this.json.readTree(body);
	}

	private static String base64Url(String value) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

}
