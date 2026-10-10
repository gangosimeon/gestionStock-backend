package com.geststock.user;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.geststock.exception.ApiException;
import com.geststock.exception.ErrorCode;
import com.geststock.support.PostgresTestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Règles des comptes : administrateur initial (mot de passe provisoire, haché, tracé) et
 * changement de son propre mot de passe (mot de passe actuel exigé, nouveau différent, politique
 * appliquée, sessions à renouveler).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@Transactional
class UserAccountServiceIT {

	private static final String PASSWORD = "Optique2026!";

	@Autowired
	private UserAccountService accounts;

	@Autowired
	private PasswordEncoder encoder;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void theInitialAdministratorHasATemporaryHashedPasswordAndIsTraced() {
		String username = "boot_" + UUID.randomUUID().toString().substring(0, 8);

		UUID id = this.accounts.createInitialAdministrator(username, "Administrateur", PASSWORD);

		Map<String, Object> user = this.jdbc.queryForMap("""
				select u.password_hash, u.must_change_password, r.code as role from app_user u
				join user_role ur on ur.user_id = u.id join role r on r.id = ur.role_id where u.id = ?""", id);
		assertThat(user.get("role")).isEqualTo("ADMIN");
		assertThat(user.get("must_change_password")).as("mot de passe provisoire").isEqualTo(true);
		assertThat((String) user.get("password_hash")).isNotEqualTo(PASSWORD)
			.satisfies(hash -> assertThat(this.encoder.matches(PASSWORD, hash)).isTrue());
		assertThat(this.jdbc.queryForObject("""
				select count(*) from audit_log where action = 'CREATE' and entity_type = 'USER' and entity_id = ?
				  and details like 'Administrateur initial%'""", Integer.class, id.toString())).isEqualTo(1);
		assertThat(this.accounts.hasActiveAdministrator()).isTrue();

		assertThatThrownBy(() -> this.accounts.createInitialAdministrator(username.toUpperCase(), "Autre", PASSWORD))
			.as("identifiant déjà pris, casse ignorée")
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.USERNAME_ALREADY_USED);
		assertThatThrownBy(() -> this.accounts.createInitialAdministrator("boot_faible", "Administrateur", "admin"))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.PASSWORD_POLICY_VIOLATION);
	}

	@Test
	void changingOwnPasswordRequiresTheCurrentOneAndANewDifferentOne() {
		UUID id = this.accounts.createInitialAdministrator("chg_" + UUID.randomUUID().toString().substring(0, 8),
				"Testeur", PASSWORD);
		Instant before = this.jdbc.queryForObject("select credentials_changed_at from app_user where id = ?",
				java.sql.Timestamp.class, id).toInstant();

		assertThatThrownBy(() -> this.accounts.changeOwnPassword(id, "Mauvais2026!", "Nouveau2026!"))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.CURRENT_PASSWORD_INVALID);
		assertThatThrownBy(() -> this.accounts.changeOwnPassword(id, PASSWORD, PASSWORD))
			.as("le nouveau mot de passe doit différer de l'actuel")
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.PASSWORD_POLICY_VIOLATION);
		assertThatThrownBy(() -> this.accounts.changeOwnPassword(id, PASSWORD, "court"))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.PASSWORD_POLICY_VIOLATION);

		this.accounts.changeOwnPassword(id, PASSWORD, "Nouveau2026!");

		Map<String, Object> user = this.jdbc.queryForMap(
				"select password_hash, must_change_password, credentials_changed_at from app_user where id = ?", id);
		assertThat(this.encoder.matches("Nouveau2026!", (String) user.get("password_hash"))).isTrue();
		assertThat(user.get("must_change_password")).as("mot de passe définitif").isEqualTo(false);
		assertThat(((java.sql.Timestamp) user.get("credentials_changed_at")).toInstant())
			.as("les jetons émis avant deviennent invalides").isAfterOrEqualTo(before);
		assertThat(this.jdbc.queryForObject("""
				select count(*) from audit_log where action = 'PASSWORD_CHANGE' and module = 'AUTH' and entity_id = ?""",
				Integer.class, id.toString())).isEqualTo(1);
	}

}
