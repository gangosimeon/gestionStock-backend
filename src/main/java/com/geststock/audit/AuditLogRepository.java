package com.geststock.audit;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Accès au journal d'audit, réservé au module {@code audit}. Seules l'insertion et la
 * lecture ont un sens : la base refuse toute modification ou suppression.
 */
interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

}
