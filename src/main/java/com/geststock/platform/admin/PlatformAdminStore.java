package com.geststock.platform.admin;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.geststock.platform.PlatformDb;

/** Accès aux comptes Super Admin ({@code platform.platform_admin}). */
@Component
class PlatformAdminStore {

	private static final String COLUMNS = "id, username, full_name, email, password_hash, active, must_change_password, "
			+ "failed_login_attempts, locked_until, credentials_changed_at, last_login_at";

	private final PlatformDb db;

	PlatformAdminStore(PlatformDb db) {
		this.db = db;
	}

	Optional<PlatformAdmin> findByUsername(String username) {
		return this.db.jdbc()
			.sql("select " + COLUMNS + " from platform.platform_admin where lower(username) = lower(:username)")
			.param("username", username)
			.query(PlatformAdminStore::map)
			.optional();
	}

	Optional<PlatformAdmin> findById(UUID id) {
		return this.db.jdbc()
			.sql("select " + COLUMNS + " from platform.platform_admin where id = :id")
			.param("id", id)
			.query(PlatformAdminStore::map)
			.optional();
	}

	boolean hasActiveAdmin() {
		return this.db.jdbc()
			.sql("select count(*) from platform.platform_admin where active")
			.query(Integer.class)
			.single() > 0;
	}

	UUID create(String username, String fullName, String passwordHash) {
		UUID id = UUID.randomUUID();
		this.db.jdbc()
			.sql("""
					insert into platform.platform_admin (id, username, full_name, password_hash, must_change_password)
					values (:id, :username, :fullName, :hash, true)""")
			.param("id", id)
			.param("username", username)
			.param("fullName", fullName)
			.param("hash", passwordHash)
			.update();
		return id;
	}

	void recordSuccess(UUID id, Instant now) {
		this.db.jdbc()
			.sql("update platform.platform_admin set failed_login_attempts = 0, locked_until = null, last_login_at = :now where id = :id")
			.param("now", Timestamp.from(now))
			.param("id", id)
			.update();
	}

	/** Échec de connexion ; verrouille le compte quand le plafond est atteint. */
	void recordFailure(UUID id, int maxAttempts, Instant lockUntil) {
		this.db.jdbc()
			.sql("""
					update platform.platform_admin
					set failed_login_attempts = failed_login_attempts + 1,
					    locked_until = case when failed_login_attempts + 1 >= :max then :lockUntil else locked_until end
					where id = :id""")
			.param("max", maxAttempts)
			.param("lockUntil", Timestamp.from(lockUntil))
			.param("id", id)
			.update();
	}

	void changePassword(UUID id, String passwordHash, Instant now) {
		this.db.jdbc()
			.sql("""
					update platform.platform_admin
					set password_hash = :hash, must_change_password = false, credentials_changed_at = :now
					where id = :id""")
			.param("hash", passwordHash)
			.param("now", Timestamp.from(now))
			.param("id", id)
			.update();
	}

	private static PlatformAdmin map(ResultSet rs, int row) throws SQLException {
		return new PlatformAdmin(rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("full_name"),
				rs.getString("email"), rs.getString("password_hash"), rs.getBoolean("active"),
				rs.getBoolean("must_change_password"), rs.getInt("failed_login_attempts"), instant(rs, "locked_until"),
				instant(rs, "credentials_changed_at"), instant(rs, "last_login_at"));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value != null ? value.toInstant() : null;
	}

}
