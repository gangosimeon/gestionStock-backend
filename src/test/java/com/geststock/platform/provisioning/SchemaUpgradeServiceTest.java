package com.geststock.platform.provisioning;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.ObjectProvider;

import com.geststock.platform.audit.PlatformAudit;
import com.geststock.tenancy.DefaultTenant;
import com.geststock.tenancy.TenantDirectory;
import com.geststock.tenancy.TenantRef;
import com.geststock.tenancy.TenantSchemaRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Hébergement à démarrage court (Vercel) : les migrations, non jouées au démarrage, le sont en
 * arrière-plan dès que le serveur est prêt ; l'entreprise n'est fermée que pendant leur application.
 */
class SchemaUpgradeServiceTest {

	private final TenantDirectory directory = mock(TenantDirectory.class);

	private final TenantMigrations migrations = mock(TenantMigrations.class);

	private final DefaultTenant defaultTenant = mock(DefaultTenant.class);

	private final TenantSchemaRegistry registry = new TenantSchemaRegistry(Clock.systemUTC());

	private final TenantRef alpha = new TenantRef(UUID.randomUUID(), "alpha");

	private SchemaUpgradeService service;

	@AfterEach
	void stop() {
		if (this.service != null) {
			this.service.destroy();
		}
	}

	@Test
	void migrationsSkippedAtStartupAreAppliedInTheBackgroundOnceTheServerIsReady() throws Exception {
		given(this.defaultTenant.get()).willReturn(Optional.empty());
		given(this.directory.activeTenants()).willReturn(List.of(this.alpha));
		given(this.migrations.pendingCount(eq(this.alpha), any())).willReturn(1);
		MigrateResult result = mock(MigrateResult.class);
		result.migrationsExecuted = 1;
		given(this.migrations.migrate(eq(this.alpha), any())).willAnswer(invocation -> {
			// Pendant l'application des migrations, l'entreprise est fermée.
			assertThat(this.registry.blockingState(this.alpha.companyId())).contains(TenantSchemaRegistry.State.UPGRADING);
			return result;
		});

		this.service = service(false);
		this.service.afterStartup();
		awaitEnd();

		SchemaUpgradeService.Status status = this.service.status();
		assertThat(status.automatic()).isTrue();
		assertThat(status.finishedAt()).isNotNull();
		assertThat(status.companies()).singleElement().satisfies(entry -> {
			assertThat(entry.state()).isEqualTo(TenantSchemaRegistry.State.UP_TO_DATE);
			assertThat(entry.pending()).isEqualTo(1);
			assertThat(entry.applied()).isEqualTo(1);
		});
		assertThat(this.registry.blockingState(this.alpha.companyId())).isEmpty();
	}

	@Test
	void migrationsAlreadyPlayedAtStartupAreNotReplayed() {
		given(this.defaultTenant.get()).willReturn(Optional.empty());

		this.service = service(true);
		this.service.afterStartup();

		assertThat(this.service.status().automatic()).isFalse();
		verifyNoInteractions(this.directory, this.migrations);
	}

	private SchemaUpgradeService service(boolean migrateOnStartup) {
		Flyway flyway = mock(Flyway.class);
		given(flyway.getConfiguration()).willReturn(mock(Configuration.class));
		@SuppressWarnings("unchecked")
		ObjectProvider<Flyway> provider = mock(ObjectProvider.class);
		given(provider.getObject()).willReturn(flyway);
		return new SchemaUpgradeService(this.directory, this.migrations, this.registry, provider, mock(PlatformAudit.class),
				Clock.systemUTC(), this.defaultTenant, migrateOnStartup);
	}

	private void awaitEnd() throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5_000;
		while (this.service.status().running() && System.currentTimeMillis() < deadline) {
			Thread.sleep(10);
		}
		assertThat(this.service.status().running()).as("mise à jour terminée").isFalse();
	}

}
