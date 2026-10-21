package com.geststock.tenancy;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Briques du routage : contexte d'entreprise et URL JDBC des bases dédiées. */
class TenancyBuildingBlocksTest {

	private final TenantRef a = new TenantRef(UUID.randomUUID(), "a");

	private final TenantRef b = new TenantRef(UUID.randomUUID(), "b");

	@AfterEach
	void clear() {
		TenantContext.clear();
	}

	@Test
	void aRequestNeverSwitchesToAnotherCompany() {
		TenantContext.set(this.a);
		TenantContext.set(this.a);
		assertThatThrownBy(() -> TenantContext.set(this.b)).isInstanceOf(IllegalStateException.class);
		assertThat(TenantContext.require()).isEqualTo(this.a);
	}

	@Test
	void callAsRestoresThePreviousContextEvenOnError() {
		assertThat(TenantContext.callAs(this.a, () -> TenantContext.require().code())).isEqualTo("a");
		assertThat(TenantContext.current()).isEmpty();
		TenantContext.set(this.a);
		assertThatThrownBy(() -> TenantContext.runAs(this.b, () -> {
			throw new IllegalStateException("échec");
		})).hasMessage("échec");
		assertThat(TenantContext.require()).isEqualTo(this.a);
	}

	@Test
	void withoutCompanyNoDatabaseIsReachable() {
		assertThatThrownBy(TenantContext::require).isInstanceOf(TenantContextMissingException.class);
	}

	@Test
	void dedicatedDatabaseUrlsKeepServerAndOptionsAndRefuseUnsafeNames() {
		assertThat(JdbcUrls.withDatabase("jdbc:postgresql://db.example:5432/postgres?sslmode=require", "geststock_company_0002"))
			.isEqualTo("jdbc:postgresql://db.example:5432/geststock_company_0002?sslmode=require");
		assertThat(JdbcUrls.databaseName("jdbc:postgresql://localhost/Geststock")).isEqualTo("geststock");
		assertThatThrownBy(() -> JdbcUrls.withDatabase("jdbc:postgresql://h/p", "x\"; drop database p; --"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> JdbcUrls.withDatabase("jdbc:postgresql://h/p", "Majuscule")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> JdbcUrls.databaseName("jdbc:mysql://h/p")).isInstanceOf(IllegalArgumentException.class);
	}

}
