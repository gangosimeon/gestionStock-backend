package com.geststock.platform.admin;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.geststock.exception.ErrorCode;
import com.geststock.exception.InvalidRequestException;
import com.geststock.exception.UnauthorizedException;
import com.geststock.platform.admin.dto.PlatformAuthDtos.AdminResponse;
import com.geststock.platform.admin.dto.PlatformAuthDtos.AuthResponse;
import com.geststock.platform.audit.PlatformAudit;
import com.geststock.security.GestStockSecurityProperties;
import com.geststock.security.JwtTokenService;

/**
 * Sessions Super Admin : connexion (verrouillage après échecs), renouvellement, déconnexion,
 * changement de mot de passe. Connexions et refus tracés dans le journal de la plateforme.
 */
@Service
public class PlatformAuthService {

	private final PlatformAdminStore admins;

	private final PlatformSessions sessions;

	private final JwtTokenService jwtTokens;

	private final PasswordEncoder passwordEncoder;

	private final PlatformAudit audit;

	private final GestStockSecurityProperties.Login loginSettings;

	private final Clock clock;

	/** Haché factice : même coût de vérification que l'identifiant existe ou non (pas d'énumération). */
	private final String dummyHash;

	PlatformAuthService(PlatformAdminStore admins, PlatformSessions sessions, JwtTokenService jwtTokens,
			PasswordEncoder passwordEncoder, PlatformAudit audit, GestStockSecurityProperties properties, Clock clock) {
		this.admins = admins;
		this.sessions = sessions;
		this.jwtTokens = jwtTokens;
		this.passwordEncoder = passwordEncoder;
		this.audit = audit;
		this.loginSettings = properties.login();
		this.clock = clock;
		this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
	}

	public AuthResponse login(String username, String password) {
		Instant now = Instant.now(this.clock);
		PlatformAdmin admin = this.admins.findByUsername(username.trim()).orElse(null);
		if (admin == null || admin.passwordHash() == null) {
			this.passwordEncoder.matches(password, this.dummyHash);
			this.audit.record("PLATFORM_LOGIN_FAILED", null, "Identifiant inconnu", null, username);
			throw new UnauthorizedException(ErrorCode.INVALID_CREDENTIALS);
		}
		if (admin.isLockedAt(now)) {
			this.audit.record("PLATFORM_LOGIN_FAILED", null, "Compte verrouillé", admin.id(), admin.username());
			throw new UnauthorizedException(ErrorCode.ACCOUNT_LOCKED);
		}
		if (!this.passwordEncoder.matches(password, admin.passwordHash())) {
			this.admins.recordFailure(admin.id(), this.loginSettings.maxFailedAttempts(),
					now.plus(this.loginSettings.lockDuration()));
			this.audit.record("PLATFORM_LOGIN_FAILED", null, "Mot de passe incorrect", admin.id(), admin.username());
			throw new UnauthorizedException(ErrorCode.INVALID_CREDENTIALS);
		}
		if (!admin.active()) {
			this.audit.record("PLATFORM_LOGIN_FAILED", null, "Compte désactivé", admin.id(), admin.username());
			throw new UnauthorizedException(ErrorCode.ACCOUNT_DISABLED);
		}
		this.admins.recordSuccess(admin.id(), now);
		this.audit.record("PLATFORM_LOGIN", null, null, admin.id(), admin.username());
		return response(admin, this.sessions.issue(admin.id()));
	}

	public AuthResponse refresh(String refreshToken) {
		PlatformSessions.Rotation rotation = this.sessions.rotate(refreshToken);
		PlatformAdmin admin = this.admins.findById(rotation.adminId())
			.filter(PlatformAdmin::active)
			.filter(found -> !found.isLockedAt(Instant.now(this.clock)))
			.orElse(null);
		if (admin == null) {
			this.sessions.revokeAll(rotation.adminId());
			throw new UnauthorizedException(ErrorCode.INVALID_REFRESH_TOKEN);
		}
		return response(admin, rotation.rawValue());
	}

	public void logout(String refreshToken) {
		this.sessions.revoke(refreshToken)
			.flatMap(this.admins::findById)
			.ifPresent(admin -> this.audit.record("PLATFORM_LOGOUT", null, null, admin.id(), admin.username()));
	}

	public AdminResponse me(UUID adminId) {
		return this.admins.findById(adminId).map(PlatformAuthService::toResponse)
			.orElseThrow(() -> new UnauthorizedException(ErrorCode.INVALID_TOKEN));
	}

	/** Change le mot de passe, ferme toutes les sessions, puis en ouvre une nouvelle. */
	public AuthResponse changePassword(UUID adminId, String currentPassword, String newPassword) {
		PlatformAdmin admin = this.admins.findById(adminId).orElseThrow(() -> new UnauthorizedException(ErrorCode.INVALID_TOKEN));
		if (admin.passwordHash() == null || !this.passwordEncoder.matches(currentPassword, admin.passwordHash())) {
			throw new InvalidRequestException(ErrorCode.CURRENT_PASSWORD_INVALID, ErrorCode.CURRENT_PASSWORD_INVALID.defaultMessage());
		}
		if (currentPassword.equals(newPassword)) {
			throw new InvalidRequestException(ErrorCode.PASSWORD_POLICY_VIOLATION,
					"Mot de passe refusé : il doit être différent de l'actuel.");
		}
		PlatformPasswordPolicy.check(newPassword, admin.username());
		// Changement à la seconde suivante : les jetons émis avant (même seconde comprise) sont rejetés.
		Instant changedAt = Instant.now(this.clock);
		this.admins.changePassword(adminId, this.passwordEncoder.encode(newPassword), changedAt);
		this.sessions.revokeAll(adminId);
		this.audit.record("PLATFORM_PASSWORD_CHANGE", null, null, admin.id(), admin.username());
		PlatformAdmin updated = this.admins.findById(adminId).orElseThrow();
		return response(updated, this.sessions.issue(adminId));
	}

	private AuthResponse response(PlatformAdmin admin, String refreshToken) {
		JwtTokenService.AccessToken token = this.jwtTokens.issuePlatform(admin.id(), admin.username());
		return new AuthResponse(token.value(), refreshToken, token.expiresInSeconds(), "Bearer", toResponse(admin));
	}

	private static AdminResponse toResponse(PlatformAdmin admin) {
		return new AdminResponse(admin.id(), admin.username(), admin.fullName(), admin.email(), admin.mustChangePassword());
	}

}
