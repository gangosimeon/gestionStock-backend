package com.geststock.permission;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Accès au catalogue des permissions, réservé au module {@code permission}. */
interface PermissionRepository extends JpaRepository<Permission, UUID> {

	Optional<Permission> findByCode(String code);

	List<Permission> findAllByCodeIn(Collection<String> codes);

}
