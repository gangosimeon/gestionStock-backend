package com.geststock.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

/**
 * Accès au journal d'audit, réservé au module {@code audit}. Seules l'insertion et la
 * lecture ont un sens : la base refuse toute modification ou suppression.
 */
interface AuditLogRepository extends JpaRepository<AuditLog, UUID>, JpaSpecificationExecutor<AuditLog> {

	long countByCreatedAtGreaterThanEqual(Instant from);

	long countByStatusAndCreatedAtGreaterThanEqual(AuditStatus status, Instant from);

	/** Utilisateurs les plus actifs depuis {@code from} : {@code [username, nombre]}. */
	@Query("""
			select a.username, count(a) from AuditLog a
			where a.username is not null and a.createdAt >= :from
			group by a.username order by count(a) desc, a.username""")
	List<Object[]> mostActiveUsers(Instant from, Pageable pageable);

	/** Actions les plus fréquentes depuis {@code from} : {@code [action, nombre]}. */
	@Query("""
			select a.action, count(a) from AuditLog a
			where a.createdAt >= :from
			group by a.action order by count(a) desc, a.action""")
	List<Object[]> mostFrequentActions(Instant from, Pageable pageable);

}
