package com.geststock.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.geststock.exception.ErrorCode;
import com.geststock.exception.UnauthorizedException;
import com.geststock.security.RefreshToken.RevocationReason;
import com.geststock.user.event.UserCredentialsChangedEvent;
import com.geststock.user.event.UserDeactivatedEvent;

/**
 * Cycle de vie des refresh tokens : émission, rotation, révocation.
 *
 * <p>Rotation : chaque renouvellement révoque le jeton présenté et en émet un nouveau dans
 * la même famille. Présenter à nouveau un jeton déjà remplacé signifie qu'il a été copié :
 * toute la famille est révoquée (le voleur comme l'utilisateur légitime doivent se
 * reconnecter).
 */
@Service
class RefreshTokenService {

	private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

	private static final int TOKEN_BYTES = 32;

	private final RefreshTokenRepository repository;

	private final GestStockSecurityProperties.Jwt properties;

	private final Clock clock;

	private final SecureRandom random = new SecureRandom();

	RefreshTokenService(RefreshTokenRepository repository, GestStockSecurityProperties properties, Clock clock) {
		this.repository = repository;
		this.properties = properties.jwt();
		this.clock = clock;
	}

	/** Nouveau jeton ; {@code familyId} nul = nouvelle connexion. Renvoie la valeur en clair. */
	@Transactional
	public String issue(UUID userId, UUID familyId, ClientInfo client) {
		return create(userId, familyId != null ? familyId : UUID.randomUUID(), client).rawValue();
	}

	/**
	 * Échange un jeton valide contre un nouveau. Les révocations décidées ici sont
	 * conservées même si l'échange est refusé.
	 */
	@Transactional(noRollbackFor = UnauthorizedException.class)
	public Rotation rotate(String rawToken, ClientInfo client) {
		RefreshToken current = this.repository.findByHashForUpdate(hash(rawToken)).orElseThrow(RefreshTokenService::invalid);
		Instant now = Instant.now(this.clock);
		if (current.isRevoked()) {
			if (current.getRevokedReason() == RevocationReason.ROTATED) {
				int revoked = this.repository.revokeFamily(current.getFamilyId(), RevocationReason.REUSE_DETECTED, now);
				log.warn("Réutilisation d'un refresh token déjà remplacé (utilisateur {}) : {} session(s) révoquée(s).",
						current.getUserId(), revoked);
			}
			throw invalid();
		}
		if (current.isExpiredAt(now)) {
			throw invalid();
		}
		Created successor = create(current.getUserId(), current.getFamilyId(), client);
		current.replaceBy(successor.id(), now);
		return new Rotation(current.getUserId(), current.getFamilyId(), successor.rawValue());
	}

	/** Déconnexion : révoque la famille du jeton présenté. Sans effet si inconnu ou déjà révoqué. */
	@Transactional
	public void revoke(String rawToken) {
		if (!StringUtils.hasText(rawToken)) {
			return;
		}
		this.repository.findByTokenHash(hash(rawToken))
			.filter(token -> !token.isRevoked())
			.ifPresent(token -> this.repository.revokeFamily(token.getFamilyId(), RevocationReason.LOGOUT,
					Instant.now(this.clock)));
	}

	@Transactional
	public void revokeAll(UUID userId, RevocationReason reason) {
		this.repository.revokeAllOfUser(userId, reason, Instant.now(this.clock));
	}

	/** Mot de passe changé : toutes les sessions de l'utilisateur sont fermées (même transaction). */
	@EventListener
	void onCredentialsChanged(UserCredentialsChangedEvent event) {
		revokeAll(event.userId(), RevocationReason.CREDENTIALS_CHANGED);
	}

	/** Compte désactivé : toutes ses sessions sont fermées (même transaction). */
	@EventListener
	void onUserDeactivated(UserDeactivatedEvent event) {
		revokeAll(event.userId(), RevocationReason.ACCOUNT_DISABLED);
	}

	private Created create(UUID userId, UUID familyId, ClientInfo client) {
		byte[] bytes = new byte[TOKEN_BYTES];
		this.random.nextBytes(bytes);
		String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		Instant now = Instant.now(this.clock);
		RefreshToken token = new RefreshToken(userId, hash(raw), familyId, now, now.plus(this.properties.refreshTokenTtl()),
				client.userAgent(), client.ipAddress());
		return new Created(this.repository.save(token).getId(), raw);
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

	private static UnauthorizedException invalid() {
		return new UnauthorizedException(ErrorCode.INVALID_REFRESH_TOKEN);
	}

	private record Created(UUID id, String rawValue) {
	}

	/** Résultat d'une rotation : nouvelle valeur en clair, à transmettre une seule fois. */
	record Rotation(UUID userId, UUID familyId, String rawValue) {

		@Override
		public String toString() {
			return "Rotation[userId=" + this.userId + ", rawValue=***]";
		}

	}

}
