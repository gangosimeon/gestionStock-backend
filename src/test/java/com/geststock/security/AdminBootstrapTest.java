package com.geststock.security;

import org.junit.jupiter.api.Test;

import org.springframework.boot.DefaultApplicationArguments;

import com.geststock.user.UserAccountService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Premier démarrage : aucun compte par défaut. L'administrateur initial n'est créé que s'il
 * n'existe aucun administrateur actif <b>et</b> que l'identifiant et le mot de passe sont fournis
 * par la configuration.
 */
class AdminBootstrapTest {

	private final UserAccountService accounts = mock(UserAccountService.class);

	@Test
	void anExistingActiveAdministratorIsNeverDuplicated() {
		given(this.accounts.hasActiveAdministrator()).willReturn(true);

		bootstrap("admin", "Optique2026!").run(new DefaultApplicationArguments());

		verify(this.accounts, never()).createInitialAdministrator(anyString(), anyString(), anyString());
	}

	@Test
	void withoutConfigurationNoAccountIsInvented() {
		given(this.accounts.hasActiveAdministrator()).willReturn(false);

		bootstrap(null, null).run(new DefaultApplicationArguments());
		bootstrap("admin", "  ").run(new DefaultApplicationArguments());
		bootstrap(" ", "Optique2026!").run(new DefaultApplicationArguments());

		verify(this.accounts, never()).createInitialAdministrator(any(), any(), any());
	}

	@Test
	void theConfiguredAdministratorIsCreatedWhenNoneIsActive() {
		given(this.accounts.hasActiveAdministrator()).willReturn(false);

		bootstrap("  admin  ", "Optique2026!").run(new DefaultApplicationArguments());

		verify(this.accounts).createInitialAdministrator("admin", "Administrateur", "Optique2026!");
	}

	private AdminBootstrap bootstrap(String username, String password) {
		return new AdminBootstrap(this.accounts, new GestStockSecurityProperties(null, null,
				new GestStockSecurityProperties.BootstrapAdmin(username, password, "Administrateur")));
	}

}
