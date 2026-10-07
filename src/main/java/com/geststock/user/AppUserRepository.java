package com.geststock.user;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Accès aux utilisateurs, réservé au module {@code user}. Les comparaisons d'identifiants
 * utilisent {@code lower(...)} pour profiter des index uniques {@code uk_app_user_username}
 * et {@code uk_app_user_email}.
 */
interface AppUserRepository extends JpaRepository<AppUser, UUID>, JpaSpecificationExecutor<AppUser> {

	/** Utilisateur avec ses rôles et dépôts, recherche insensible à la casse. */
	@EntityGraph(attributePaths = { "roles", "warehouses" })
	@Query("select u from AppUser u where lower(u.username) = lower(:username)")
	Optional<AppUser> findWithAccessByUsername(@Param("username") String username);

	@Query("select count(u) > 0 from AppUser u where lower(u.username) = lower(:username)")
	boolean existsByUsernameIgnoringCase(@Param("username") String username);

	@Query("""
			select count(u) > 0 from AppUser u
			where lower(u.username) = lower(:username) and u.id <> :excludedId""")
	boolean existsByUsernameIgnoringCaseAndIdNot(@Param("username") String username, @Param("excludedId") UUID excludedId);

	@Query("select count(u) > 0 from AppUser u where lower(u.email) = lower(:email)")
	boolean existsByEmailIgnoringCase(@Param("email") String email);

	@Query("""
			select count(u) > 0 from AppUser u
			where lower(u.email) = lower(:email) and u.id <> :excludedId""")
	boolean existsByEmailIgnoringCaseAndIdNot(@Param("email") String email, @Param("excludedId") UUID excludedId);

	/** Nombre d'utilisateurs actifs portant le rôle donné, hors l'utilisateur indiqué. */
	@Query("""
			select count(distinct u) from AppUser u join u.roles r
			where u.active = true and r.code = :roleCode and u.id <> :excludedId""")
	long countActiveWithRoleExcluding(@Param("roleCode") String roleCode, @Param("excludedId") UUID excludedId);

}
