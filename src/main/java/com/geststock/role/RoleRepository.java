package com.geststock.role;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Accès aux rôles, réservé au module {@code role}. */
interface RoleRepository extends JpaRepository<Role, UUID> {

	/** Rôle avec ses permissions chargées en une requête (pas de N+1). */
	@EntityGraph(attributePaths = "permissions")
	Optional<Role> findWithPermissionsByCode(String code);

	List<Role> findAllByCodeIn(Collection<String> codes);

	@EntityGraph(attributePaths = "permissions")
	@Query("select r from Role r order by r.builtIn desc, r.code asc")
	List<Role> findAllWithPermissions();

	@EntityGraph(attributePaths = "permissions")
	@Query("select r from Role r where r.id = :id")
	Optional<Role> findWithPermissionsById(@Param("id") UUID id);

	boolean existsByCode(String code);

	/** Nombre d'utilisateurs portant le rôle (table de jointure user_role, actifs ou non). */
	@Query(value = "select count(*) from user_role where role_id = :roleId", nativeQuery = true)
	long countUsers(@Param("roleId") UUID roleId);

}
