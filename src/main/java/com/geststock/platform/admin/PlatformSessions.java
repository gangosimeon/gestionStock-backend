package com.geststock.platform.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.geststock.exception.ErrorCode;
import com.geststock.exception.UnauthorizedException;
import com.geststock.platform.PlatformDb;
import com.geststock.security.GestStockSecurityProperties;

/**
 * Sessions Super Admin : refresh tokens opaques (256 bits) à usage unique, seul leur haché SHA-256 est
 * stocké. Réutiliser un jeton déjà échangé révoque toute la session (vol présumé).
 */
@Component
class PlatformSessions {

	private static final Logger log = LoggerFactory.getLogger(PlatformSessions.class);

	private static final int TOKEN_BYTES = 32;

	private final PlatformDb db;

	private final GestStockSecurityProperties.Jwt properties;

	private final Clock clock;

	private final SecureRandom random = new SecureRandom();

	PlatformSessions(PlatformDb db, GestStockSecurityProperties properties, Clock clock) {
		this.db = db;
		this.properties = properties.jwt();
		this.clock = clock;
	}

	String issue(UUID adminId) {
		return create(adminId, UUID.randomUUID());
	}

	/** Échange un jeton valide contre un nouveau ; renvoie l'administrateur et le nouveau jeton. */
	Rotation rotate(String rawToken) {
		// Révocations validées même si l'échange est refusé : le refus est levé hors transaction.
		Rotation rotation = this.db.inTransaction(status -> {
			Row current = this.db.jdbc()
				.sql("select id, admin_id, family_id, expires_at, revoked_at from platform.platform_refresh_token "
						+ "where token_hash = :hash for update")
				.param("hash", hash(rawToken))
				.query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getObject("admin_id", UUID.class),
						rs.getObject("family_id", UUID.class), rs.getTimestamp("expires_at").toInstant(),
						rs.getTimestamp("revoked_at") != null))
				.optional()
				.orElse(null);
			if (current == null) {
				return null;
			}
			Instant now = Instant.now(this.clock);
			if (current.revoked()) {
				revokeFamily(current.familyId(), now);
				log.warn("Réutilisation d'un refresh token Super Admin déjà échangé : session révoquée.");
				return null;
			}
			if (!current.expiresAt().isAfter(now)) {
				return null;
			}
			this.db.jdbc()
				.sql("update platform.platform_refresh_token set revoked_at = :now where id = :id")
				.param("now", Timestamp.from(now))
				.param("id", current.id())
				.update();
			return new Rotation(current.adminId(), create(current.adminId(), current.familyId()));
		});
		if (rotation == null) {
			throw invalid();
		}
		return rotation;
	}

	Optional<UUID> revoke(String rawToken) {
		if (!StringUtils.hasText(rawToken)) {
			return Optional.empty();
		}
		return this.db.inTransaction(status -> {
			Optional<Row> row = this.db.jdbc()
				.sql("select id, admin_id, family_id, expires_at, revoked_at from platform.platform_refresh_token where token_hash = :hash")
				.param("hash", hash(rawToken))
				.query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getObject("admin_id", UUID.class),
						rs.getObject("family_id", UUID.class), rs.getTimestamp("expires_at").toInstant(),
						rs.getTimestamp("revoked_at") != null))
				.optional()
				.filter(r -> !r.revoked());
			row.ifPresent(r -> revokeFamily(r.familyId(), Instant.now(this.clock)));
			return row.map(Row::adminId);
		});
	}

	void revokeAll(UUID adminId) {
		this.db.jdbc()
			.sql("update platform.platform_refresh_token set revoked_at = :now where admin_id = :id and revoked_at is null")
			.param("now", Timestamp.from(Instant.now(this.clock)))
			.param("id", adminId)
			.update();
	}

	private void revokeFamily(UUID familyId, Instant now) {
		this.db.jdbc()
			.sql("update platform.platform_refresh_token set revoked_at = :now where family_id = :family and revoked_at is null")
			.param("now", Timestamp.from(now))
			.param("family", familyId)
			.update();
	}

	private String create(UUID adminId, UUID familyId) {
		byte[] bytes = new byte[TOKEN_BYTES];
		this.random.nextBytes(bytes);
		String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		Instant now = Instant.now(this.clock);
		this.db.jdbc()
			.sql("""
					insert into platform.platform_refresh_token (admin_id, token_hash, family_id, issued_at, expires_at)
					values (:admin, :hash, :family, :now, :expires)""")
			.param("admin", adminId)
			.param("hash", hash(raw))
			.param("family", familyId)
			.param("now", Timestamp.from(now))
			.param("expires", Timestamp.from(now.plus(this.properties.refreshTokenTtl())))
			.update();
		return raw;
	}

	static String hash(String rawToken) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 indisponible", ex);
		}
	}

	static UnauthorizedException invalid() {
		return new UnauthorizedException(ErrorCode.INVALID_REFRESH_TOKEN);
	}

	record Rotation(UUID adminId, String rawValue) {

		@Override
		public String toString() {
			return "Rotation[adminId=" + this.adminId + ", rawValue=***]";
		}

	}

	private record Row(UUID id, UUID adminId, UUID familyId, Instant expiresAt, boolean revoked) {
	}

}
