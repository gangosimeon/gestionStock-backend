package com.geststock.user;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.geststock.common.security.ActorIdentity;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.InvalidRequestException;

/**
 * Liens d'accès à usage unique de la base de l'entreprise courante : activation du compte initial,
 * réinitialisation d'un mot de passe. Jeton de 256 bits ; seul son haché SHA-256 est stocké ; un nouveau
 * lien invalide les liens précédents du même compte et du même usage. Le lien en clair n'est jamais
 * journalisé.
 */
@Service
public class AccountAccessService {

	public static final Duration ACTIVATION_TTL = Duration.ofDays(7);

	public static final Duration RESET_TTL = Duration.ofHours(48);

	private static final int TOKEN_BYTES = 32;

	private final NamedParameterJdbcTemplate jdbc;

	private final UserAccountService accounts;

	private final Clock clock;

	private final SecureRandom random = new SecureRandom();

	AccountAccessService(NamedParameterJdbcTemplate jdbc, UserAccountService accounts, Clock clock) {
		this.jdbc = jdbc;
		this.accounts = accounts;
		this.clock = clock;
	}

	/** Usage d'un lien. */
	public enum Purpose {
		ACTIVATION, PASSWORD_RESET
	}

	/**
	 * Lien émis.
	 *
	 * @param token     jeton en clair (à transmettre une seule fois)
	 * @param expiresAt fin de validité
	 */
	public record AccessLink(String token, Instant expiresAt) {

		@Override
		public String toString() {
			return "AccessLink[token=***, expiresAt=" + this.expiresAt + "]";
		}

	}

	@Transactional
	public AccessLink issue(UUID userId, Purpose purpose) {
		Instant now = Instant.now(this.clock);
		this.jdbc.update("""
				update account_access_token set used_at = :now
				where user_id = :user and purpose = :purpose and used_at is null""",
				Map.of("now", Timestamp.from(now), "user", userId, "purpose", purpose.name()));
		byte[] bytes = new byte[TOKEN_BYTES];
		this.random.nextBytes(bytes);
		String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		Instant expiresAt = now.plus(purpose == Purpose.ACTIVATION ? ACTIVATION_TTL : RESET_TTL);
		var params = new java.util.HashMap<String, Object>();
		params.put("user", userId);
		params.put("purpose", purpose.name());
		params.put("hash", hash(raw));
		params.put("expires", Timestamp.from(expiresAt));
		params.put("now", Timestamp.from(now));
		params.put("by", ActorIdentity.currentUserId().orElse(null));
		this.jdbc.update("""
				insert into account_access_token (user_id, purpose, token_hash, expires_at, created_at, created_by)
				values (:user, :purpose, :hash, :expires, :now, :by)""", params);
		return new AccessLink(raw, expiresAt);
	}

	/** Définit le mot de passe avec un lien valide, puis le consomme. */
	@Transactional
	public void usePassword(String rawToken, String newPassword) {
		Instant now = Instant.now(this.clock);
		UUID userId = this.jdbc.query("""
				select user_id from account_access_token
				where token_hash = :hash and used_at is null and expires_at > :now
				for update""", Map.of("hash", hash(rawToken), "now", Timestamp.from(now)),
				(rs, n) -> rs.getObject("user_id", UUID.class))
			.stream()
			.findFirst()
			.orElseThrow(() -> new InvalidRequestException(ErrorCode.ACTIVATION_TOKEN_INVALID,
					ErrorCode.ACTIVATION_TOKEN_INVALID.defaultMessage()));
		this.accounts.setPasswordFromAccessLink(userId, newPassword);
		this.jdbc.update("update account_access_token set used_at = :now where token_hash = :hash",
				Map.of("now", Timestamp.from(now), "hash", hash(rawToken)));
	}

	static String hash(String rawToken) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 indisponible", ex);
		}
	}

}
