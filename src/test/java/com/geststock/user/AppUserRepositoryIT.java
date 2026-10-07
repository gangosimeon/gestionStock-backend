package com.geststock.user;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.hibernate.Hibernate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.geststock.role.Role;
import com.geststock.support.PostgresDataJpaTest;
import com.geststock.warehouse.Warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Accès JPA aux utilisateurs : relations rôles et dépôts, unicité, auteur des modifications. */
@PostgresDataJpaTest
class AppUserRepositoryIT {

	@Autowired
	private AppUserRepository repository;

	@Autowired
	private EntityManager entityManager;

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void persistsUserWithRolesWarehousesAndDefaultWarehouse() {
		Warehouse main = mainWarehouse();
		AppUser user = new AppUser("pierre.d", "Pierre", "Diallo");
		user.assignRole(role("CAISSIER"));
		user.grantWarehouse(main);
		user.setDefaultWarehouse(main);
		this.repository.saveAndFlush(user);
		this.entityManager.clear();

		AppUser reloaded = this.repository.findWithAccessByUsername("PIERRE.D").orElseThrow();

		assertThat(Hibernate.isInitialized(reloaded.getRoles())).isTrue();
		assertThat(reloaded.getRoles()).extracting(Role::getCode).containsExactly("CAISSIER");
		assertThat(reloaded.getWarehouses()).extracting(Warehouse::getCode).containsExactly("MAG-01");
		assertThat(reloaded.getDefaultWarehouse().getCode()).isEqualTo("MAG-01");
		assertThat(reloaded.getPasswordHash()).as("compte sans mot de passe").isNull();
		assertThat(reloaded.isMustChangePassword()).isTrue();
		assertThat(reloaded.isActive()).isTrue();
	}

	@Test
	void usernameIsUniqueIgnoringCase() {
		this.repository.saveAndFlush(new AppUser("marie.k", "Marie", "Koné"));

		assertThat(this.repository.existsByUsernameIgnoringCase("MARIE.K")).isTrue();
		assertThatThrownBy(() -> this.repository.saveAndFlush(new AppUser("Marie.K", "Autre", "Marie")))
			.isInstanceOf(DataIntegrityViolationException.class)
			.satisfies(ex -> assertThat(constraintName(ex)).isEqualTo("uk_app_user_username"));
	}

	@Test
	void recordsAuthenticatedUserAsAuthorOfChanges() {
		UUID authorId = UUID.randomUUID();
		SecurityContextHolder.getContext()
			.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(authorId.toString(), null, List.of()));

		AppUser saved = this.repository.saveAndFlush(new AppUser("fatou.s", "Fatou", "Sow"));

		assertThat(saved.getCreatedBy()).isEqualTo(authorId);
		assertThat(saved.getUpdatedBy()).isEqualTo(authorId);
	}

	@Test
	void rejectsInvalidUsernameThroughCheckConstraint() {
		assertThatThrownBy(() -> this.repository.saveAndFlush(new AppUser("a b", null, "Espace interdit")))
			.isInstanceOf(DataIntegrityViolationException.class)
			.satisfies(ex -> assertThat(constraintName(ex)).isEqualTo("ck_app_user_username_format"));
	}

	private Role role(String code) {
		return this.entityManager.createQuery("select r from Role r where r.code = :code", Role.class)
			.setParameter("code", code)
			.getSingleResult();
	}

	private Warehouse mainWarehouse() {
		return this.entityManager.createQuery("select w from Warehouse w where w.code = 'MAG-01'", Warehouse.class)
			.getSingleResult();
	}

	private static String constraintName(Throwable ex) {
		Throwable cause = ex;
		while (cause != null) {
			if (cause instanceof org.hibernate.exception.ConstraintViolationException violation) {
				return violation.getConstraintName();
			}
			cause = cause.getCause();
		}
		return null;
	}

}
