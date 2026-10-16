package com.geststock.role;

import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import com.geststock.permission.Permission;
import com.geststock.support.PostgresDataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

/** Rôles système et matrice de permissions, chargés via JPA. */
@PostgresDataJpaTest
class RoleRepositoryIT {

	@Autowired
	private RoleRepository repository;

	@Test
	void cashierRoleCarriesOnlyItsPermissions() {
		Role cashier = this.repository.findWithPermissionsByCode("CAISSIER").orElseThrow();

		assertThat(cashier.isBuiltIn()).isTrue();
		assertThat(Hibernate.isInitialized(cashier.getPermissions())).as("chargé par l'EntityGraph").isTrue();
		assertThat(cashier.getPermissions()).extracting(Permission::getCode)
			.hasSize(15)
			.contains("sale:create", "cash:operate", "customer:pay", "customer:create", "customer:update",
					"supplier:read")
			.doesNotContain("stock:adjust", "user:manage", "audit:read", "sale:cancel");
	}

	@Test
	void adminRoleHasEveryPermission() {
		Role admin = this.repository.findWithPermissionsByCode("ADMIN").orElseThrow();

		assertThat(admin.getPermissions()).hasSize(48);
	}

}
