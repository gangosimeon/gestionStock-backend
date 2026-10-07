package com.geststock.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.geststock.user.UserAccount;
import com.geststock.user.UserAccountService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LoginAuthenticationProviderTest {

	private static final Instant NOW = Instant.parse("2026-10-06T10:00:00Z");

	private static final UUID ID = UUID.randomUUID();

	@Mock
	private UserAccountService accounts;

	@Mock
	private PasswordEncoder encoder;

	private LoginAuthenticationProvider provider;

	@BeforeEach
	void setUp() {
		given(this.encoder.encode(anyString())).willReturn("{bcrypt}dummy");
		GestStockSecurityProperties properties = new GestStockSecurityProperties(null,
				new GestStockSecurityProperties.Login(3, Duration.ofMinutes(15)), null);
		this.provider = new LoginAuthenticationProvider(this.accounts, this.encoder, properties,
				Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void authenticatesValidCredentialsAndResetsFailures() {
		given(this.accounts.findByUsername("pierre.d")).willReturn(Optional.of(account(true, "{bcrypt}hash", null)));
		given(this.encoder.matches("Optique2026", "{bcrypt}hash")).willReturn(true);

		Authentication result = this.provider.authenticate(token("pierre.d", "Optique2026"));

		assertThat(result.isAuthenticated()).isTrue();
		assertThat(result.getAuthorities()).as("aucune autorité accordée au login").isEmpty();
		verify(this.accounts).recordSuccessfulLogin(ID);
	}

	@Test
	void unknownUserStillComputesAHashAndFailsGenerically() {
		given(this.accounts.findByUsername("inconnu")).willReturn(Optional.empty());

		assertThatThrownBy(() -> this.provider.authenticate(token("inconnu", "x")))
			.isInstanceOf(BadCredentialsException.class);
		verify(this.encoder).matches("x", "{bcrypt}dummy");
	}

	@Test
	void wrongPasswordIsCountedAsFailure() {
		given(this.accounts.findByUsername("pierre.d")).willReturn(Optional.of(account(true, "{bcrypt}hash", null)));
		given(this.encoder.matches("mauvais", "{bcrypt}hash")).willReturn(false);

		assertThatThrownBy(() -> this.provider.authenticate(token("pierre.d", "mauvais")))
			.isInstanceOf(BadCredentialsException.class);
		verify(this.accounts).recordFailedLogin(ID, 3, Duration.ofMinutes(15));
	}

	@Test
	void accountWithoutPasswordCannotLogIn() {
		given(this.accounts.findByUsername("pierre.d")).willReturn(Optional.of(account(true, null, null)));

		assertThatThrownBy(() -> this.provider.authenticate(token("pierre.d", "")))
			.isInstanceOf(BadCredentialsException.class);
	}

	@Test
	void lockedAccountIsRejectedBeforePasswordCheck() {
		given(this.accounts.findByUsername("pierre.d"))
			.willReturn(Optional.of(account(true, "{bcrypt}hash", NOW.plusSeconds(60))));

		assertThatThrownBy(() -> this.provider.authenticate(token("pierre.d", "Optique2026")))
			.isInstanceOf(LockedException.class);
		verify(this.encoder, never()).matches(eq("Optique2026"), eq("{bcrypt}hash"));
	}

	@Test
	void disabledStateIsRevealedOnlyAfterCorrectPassword() {
		given(this.accounts.findByUsername("pierre.d")).willReturn(Optional.of(account(false, "{bcrypt}hash", null)));
		given(this.encoder.matches("Optique2026", "{bcrypt}hash")).willReturn(true);

		assertThatThrownBy(() -> this.provider.authenticate(token("pierre.d", "Optique2026")))
			.isInstanceOf(DisabledException.class);
		verify(this.accounts, never()).recordSuccessfulLogin(ID);
	}

	private static UsernamePasswordAuthenticationToken token(String username, String password) {
		return UsernamePasswordAuthenticationToken.unauthenticated(username, password);
	}

	private static UserAccount account(boolean active, String hash, Instant lockedUntil) {
		return new UserAccount(ID, "pierre.d", hash, active, false, lockedUntil, null, Set.of("CAISSIER"), null, Set.of());
	}

}
