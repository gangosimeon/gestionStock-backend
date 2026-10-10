package com.geststock.security;

import java.time.Clock;
import java.time.Instant;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

import com.geststock.audit.AuditService;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.UnauthorizedException;
import com.geststock.role.RoleService;
import com.geststock.security.RefreshToken.RevocationReason;
import com.geststock.security.dto.AuthResponse;
import com.geststock.security.dto.MeResponse;
import com.geststock.user.UserAccount;
import com.geststock.user.UserAccountService;

/**
 * Sessions : connexion, renouvellement, déconnexion, profil courant, changement de mot de
 * passe. Le serveur est seul juge de l'identité et des droits.
 */
@Service
public class AuthService {

	private final AuthenticationManager loginManager;

	private final UserAccountService accounts;

	private final RoleService roles;

	private final JwtTokenService jwtTokens;

	private final RefreshTokenService refreshTokens;

	private final AuditService audit;

	private final Clock clock;

	AuthService(LoginAuthenticationProvider loginProvider, UserAccountService accounts, RoleService roles,
			JwtTokenService jwtTokens, RefreshTokenService refreshTokens, AuditService audit, Clock clock) {
		this.loginManager = new ProviderManager(loginProvider);
		this.accounts = accounts;
		this.roles = roles;
		this.jwtTokens = jwtTokens;
		this.refreshTokens = refreshTokens;
		this.audit = audit;
		this.clock = clock;
	}

	public AuthResponse login(String username, String password, ClientInfo client) {
		Authentication authentication;
		try {
			authentication = this.loginManager
				.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(username, password));
		}
		catch (LockedException ex) {
			throw loginRefused(username, ErrorCode.ACCOUNT_LOCKED);
		}
		catch (DisabledException ex) {
			throw loginRefused(username, ErrorCode.ACCOUNT_DISABLED);
		}
		catch (AuthenticationException ex) {
			throw loginRefused(username, ErrorCode.INVALID_CREDENTIALS);
		}
		UserAccount account = (UserAccount) authentication.getPrincipal();
		String refreshToken = this.refreshTokens.issue(account.id(), null, client);
		AuthResponse response = response(account, refreshToken);
		this.audit.authentication("LOGIN", account.id(), account.username(), account.roles());
		return response;
	}

	/**
	 * Renouvelle la session. Le compte est revérifié : un utilisateur désactivé ou verrouillé
	 * depuis sa connexion ne peut pas prolonger sa session.
	 */
	public AuthResponse refresh(String refreshToken, ClientInfo client) {
		RefreshTokenService.Rotation rotation = this.refreshTokens.rotate(refreshToken, client);
		UserAccount account = this.accounts.findById(rotation.userId())
			.filter(UserAccount::active)
			.filter(found -> !found.isLockedAt(Instant.now(this.clock)))
			.orElse(null);
		if (account == null) {
			this.refreshTokens.revokeAll(rotation.userId(), RevocationReason.ACCOUNT_DISABLED);
			throw new UnauthorizedException(ErrorCode.INVALID_REFRESH_TOKEN);
		}
		return response(account, rotation.rawValue());
	}

	/**
	 * Ferme la session associée au refresh token. Idempotent, ne révèle rien. La trace porte le
	 * propriétaire du jeton, pas une identité déclarée par le client.
	 */
	public void logout(String refreshToken) {
		this.refreshTokens.revoke(refreshToken)
			.flatMap(this.accounts::findById)
			.ifPresent(account -> this.audit.authentication("LOGOUT", account.id(), account.username(),
					account.roles()));
	}

	/** Connexion refusée : tracée (nom saisi, motif), puis renvoyée au client. */
	private UnauthorizedException loginRefused(String username, ErrorCode code) {
		this.audit.loginFailed(username, code.name());
		return new UnauthorizedException(code);
	}

	public MeResponse me(CurrentUser user) {
		return new MeResponse(this.accounts.profile(user.id()), user.permissions());
	}

	/** Change le mot de passe, ferme toutes les sessions, puis en ouvre une nouvelle. */
	public AuthResponse changePassword(CurrentUser user, String currentPassword, String newPassword, ClientInfo client) {
		this.accounts.changeOwnPassword(user.id(), currentPassword, newPassword);
		UserAccount account = this.accounts.findById(user.id())
			.orElseThrow(() -> new UnauthorizedException(ErrorCode.INVALID_TOKEN));
		return response(account, this.refreshTokens.issue(account.id(), null, client));
	}

	private AuthResponse response(UserAccount account, String refreshToken) {
		JwtTokenService.AccessToken accessToken = this.jwtTokens.issue(account);
		MeResponse user = new MeResponse(this.accounts.profile(account.id()), this.roles.permissionsOf(account.roles()));
		return new AuthResponse(accessToken.value(), refreshToken, accessToken.expiresInSeconds(), user);
	}

}
