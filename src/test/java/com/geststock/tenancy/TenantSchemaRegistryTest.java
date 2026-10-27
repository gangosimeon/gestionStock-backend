package com.geststock.tenancy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * État du schéma des bases : seules une mise à jour en cours et une mise à jour en échec ferment
 * l'accès ; une base pas encore vérifiée reste ouverte (aucune interruption à chaque démarrage).
 */
class TenantSchemaRegistryTest {

	private final TenantSchemaRegistry registry = new TenantSchemaRegistry(
			Clock.fixed(Instant.parse("2026-10-27T10:00:00Z"), ZoneOffset.UTC));

	private final TenantRef alpha = new TenantRef(UUID.randomUUID(), "alpha");

	@Test
	void aDatabaseNotYetCheckedStaysOpen() {
		assertThat(this.registry.blockingState(this.alpha.companyId())).isEmpty();
		assertThat(this.registry.entries()).isEmpty();
	}

	@Test
	void anUpgradeInProgressOrFailedClosesAccessUntilTheDatabaseIsUpToDate() {
		this.registry.upgrading(this.alpha, 2);
		assertThat(this.registry.blockingState(this.alpha.companyId())).contains(TenantSchemaRegistry.State.UPGRADING);

		this.registry.failed(this.alpha, 2, "échec");
		assertThat(this.registry.blockingState(this.alpha.companyId())).contains(TenantSchemaRegistry.State.FAILED);

		this.registry.upToDate(this.alpha, 2, 2);
		assertThat(this.registry.blockingState(this.alpha.companyId())).isEmpty();
	}

	@Test
	void entriesKeepTheLastResultPerCompanySortedByCode() {
		TenantRef beta = new TenantRef(UUID.randomUUID(), "beta");
		this.registry.upToDate(beta, 0, 0);
		this.registry.failed(this.alpha, 1, "Migration impossible");

		assertThat(this.registry.entries()).extracting(TenantSchemaRegistry.Entry::code).containsExactly("alpha", "beta");
		TenantSchemaRegistry.Entry alphaEntry = this.registry.entries().get(0);
		assertThat(alphaEntry.state()).isEqualTo(TenantSchemaRegistry.State.FAILED);
		assertThat(alphaEntry.pending()).isEqualTo(1);
		assertThat(alphaEntry.message()).isEqualTo("Migration impossible");
		assertThat(alphaEntry.updatedAt()).isEqualTo(Instant.parse("2026-10-27T10:00:00Z"));
	}

}
