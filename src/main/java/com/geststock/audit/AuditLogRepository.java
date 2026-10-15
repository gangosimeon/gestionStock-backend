package com.geststock.audit;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Accès au journal d'audit, réservé au module {@code audit}. Seules l'insertion et la
 * lecture ont un sens : la base refuse toute modification ou suppression.
 */
interface AuditLogRepository extends JpaRepository<AuditLog, UUID>, JpaSpecificationExecutor<AuditLog> {

	/**
	 * Utilisateurs les plus actifs depuis {@code from} : {@code [username, nombre]}, sur tous les
	 * dépôts ({@code all}) ou sur les entrées des dépôts {@code warehouseIds} (non vide).
	 */
	@Query("""
			select a.username, count(a) from AuditLog a
			where a.username is not null and a.createdAt >= :from
			  and (:all = true or a.warehouseId in :warehouseIds)
			group by a.username order by count(a) desc, a.username""")
	List<Object[]> mostActiveUsers(@Param("from") Instant from, @Param("all") boolean all,
			@Param("warehouseIds") Collection<UUID> warehouseIds, Pageable pageable);

	/** Actions les plus fréquentes depuis {@code from} : {@code [action, nombre]}, même périmètre. */
	@Query("""
			select a.action, count(a) from AuditLog a
			where a.createdAt >= :from
			  and (:all = true or a.warehouseId in :warehouseIds)
			group by a.action order by count(a) desc, a.action""")
	List<Object[]> mostFrequentActions(@Param("from") Instant from, @Param("all") boolean all,
			@Param("warehouseIds") Collection<UUID> warehouseIds, Pageable pageable);

}
