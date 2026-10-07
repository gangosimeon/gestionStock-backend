package com.geststock.security;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.geststock.user.UserAccount;
import com.geststock.user.UserAccountService;

/**
 * Vérification identifiant / mot de passe pour {@code POST /api/auth/login}.
 *
 * <p>Protections :
 * <ul>
 * <li>message identique pour un identifiant inconnu, un mauvais mot de passe ou un compte
 * sans mot de passe (pas d'énumération des comptes) ;</li>
 * <li>temps de réponse comparable : un haché factice est vérifié quand le compte n'existe pas ;</li>
 * <li>verrouillage temporaire après N échecs consécutifs ;</li>
 * <li>l'état « désactivé » n'est révélé qu'après un mot de passe correct.</li>
 * </ul>
 */
@Component
public class LoginAuthenticationProvider implements AuthenticationProvider {

	private final UserAccountService accounts;

	private final PasswordEncoder passwordEncoder;

	private final GestStockSecurityProperties.Login policy;

	private final Clock clock;

	/** Haché d'une valeur aléatoire, vérifié quand le compte n'existe pas (temps constant). */
	private final String dummyHash;

	LoginAuthenticationProvider(UserAccountService accounts, PasswordEncoder passwordEncoder,
			GestStockSecurityProperties properties, Clock clock) {
		this.accounts = accounts;
		this.passwordEncoder = passwordEncoder;
		this.policy = properties.login();
		this.clock = clock;
		this.dummyHash = passwordEncoder.encode(java.util.UUID.randomUUID().toString());
	}

	@Override
	public Authentication authenticate(Authentication authentication) {
		String username = String.valueOf(authentication.getPrincipal()).trim();
		String password = authentication.getCredentials() != null ? authentication.getCredentials().toString() : "";

		Optional<UserAccount> found = this.accounts.findByUsername(username);
		if (found.isEmpty()) {
			this.passwordEncoder.matches(password, this.dummyHash);
			throw new BadCredentialsException("Identifiants invalides");
		}
		UserAccount account = found.get();
		if (account.isLockedAt(Instant.now(this.clock))) {
			throw new LockedException("Compte verrouillé");
		}
		boolean matches;
		if (account.hasPassword()) {
			matches = this.passwordEncoder.matches(password, account.passwordHash());
		}
		else {
			// Compte sans mot de passe : même coût de calcul, refus systématique.
			this.passwordEncoder.matches(password, this.dummyHash);
			matches = false;
		}
		if (!matches) {
			this.accounts.recordFailedLogin(account.id(), this.policy.maxFailedAttempts(), this.policy.lockDuration());
			throw new BadCredentialsException("Identifiants invalides");
		}
		if (!account.active()) {
			throw new DisabledException("Compte désactivé");
		}
		this.accounts.recordSuccessfulLogin(account.id());
		return UsernamePasswordAuthenticationToken.authenticated(account, null, java.util.List.of());
	}

	@Override
	public boolean supports(Class<?> authentication) {
		return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
	}

}
