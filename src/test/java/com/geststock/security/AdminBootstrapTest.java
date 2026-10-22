package com.geststock.security;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import org.springframework.boot.DefaultApplicationArguments;

import com.geststock.platform.PlatformProperties;
import com.geststock.tenancy.DefaultTenant;
import com.geststock.tenancy.TenantContext;
import com.geststock.tenancy.TenantDirectory;
import com.geststock.tenancy.TenantRef;
import com.geststock.user.UserAccountService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Premier démarrage : aucun compte par défaut. L'administrateur initial n'est créé que s'il
 * n'existe aucun administrateur actif <b>et</b> que l'identifiant et le mot de passe sont fournis
 * par la configuration. La base visée est désignée explicitement (aucune entreprise courante au
 * démarrage).
 */
class AdminBootstrapTest {

	private static final TenantRef LEGACY = new TenantRef(UUID.randomUUID(), "principale");

	private final UserAccountService accounts = mock(UserAccountService.class);

	private final TenantDirectory directory = mock(TenantDirectory.class);

	private final DefaultTenant defaultTenant = mock(DefaultTenant.class);

	@Test
	void anExistingActiveAdministratorIsNeverDuplicated() {
		given(this.defaultTenant.get()).willReturn(Optional.of(LEGACY));
		given(this.accounts.hasActiveAdministrator()).willReturn(true);

		bootstrap("admin", "Optique2026!", null).run(new DefaultApplicationArguments());

		verify(this.accounts, never()).createInitialAdministrator(anyString(), anyString(), anyString());
	}

	@Test
	void withoutConfigurationNoAccountIsInvented() {
		given(this.defaultTenant.get()).willReturn(Optional.of(LEGACY));
		given(this.accounts.hasActiveAdministrator()).willReturn(false);

		bootstrap(null, null, null).run(new DefaultApplicationArguments());
		bootstrap("admin", "  ", null).run(new DefaultApplicationArguments());
		bootstrap(" ", "Optique2026!", null).run(new DefaultApplicationArguments());

		verify(this.accounts, never()).createInitialAdministrator(any(), any(), any());
	}

	@Test
	void theConfiguredAdministratorIsCreatedWhenNoneIsActive() {
		given(this.defaultTenant.get()).willReturn(Optional.of(LEGACY));
		given(this.accounts.hasActiveAdministrator()).willReturn(false);

		bootstrap("  admin  ", "Optique2026!", null).run(new DefaultApplicationArguments());

		verify(this.accounts).createInitialAdministrator("admin", "Administrateur", "Optique2026!");
	}

	/** Production : pas d'entreprise par défaut ; la base historique est visée explicitement. */
	@Test
	void inProductionTheLegacyCompanyIsTargetedExplicitly() {
		given(this.defaultTenant.get()).willReturn(Optional.empty());
		given(this.directory.findByCode("principale"))
			.willReturn(Optional.of(new TenantDirectory.TenantStatus(LEGACY, "ACTIVE", "Principale")));
		AtomicReference<TenantRef> seen = new AtomicReference<>();
		given(this.accounts.hasActiveAdministrator()).willAnswer(invocation -> {
			seen.set(TenantContext.require());
			return true;
		});

		bootstrap("admin", "Optique2026!", " Principale ").run(new DefaultApplicationArguments());

		assertThat(seen.get()).isEqualTo(LEGACY);
		assertThat(TenantContext.current()).isEmpty();
	}

	/** Ni base historique ni entreprise par défaut : aucune base n'est touchée au démarrage. */
	@Test
	void withoutLegacyOrDefaultCompanyNothingIsTouched() {
		given(this.defaultTenant.get()).willReturn(Optional.empty());

		bootstrap("admin", "Optique2026!", null).run(new DefaultApplicationArguments());

		verifyNoInteractions(this.accounts);
	}

	private AdminBootstrap bootstrap(String username, String password, String legacyCode) {
		return new AdminBootstrap(this.accounts,
				new GestStockSecurityProperties(null, null,
						new GestStockSecurityProperties.BootstrapAdmin(username, password, "Administrateur")),
				new PlatformProperties(null, null, null, new PlatformProperties.LegacyCompany(legacyCode, "Principale"),
						null),
				this.directory, this.defaultTenant);
	}

}
