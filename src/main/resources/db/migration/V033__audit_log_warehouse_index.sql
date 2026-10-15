-- =============================================================================
-- V033 — Journal d'audit consulté par dépôt
--
-- Le journal se filtre désormais par dépôt et se limite au périmètre du lecteur (D9) : index
-- sur le dépôt, dans l'ordre de lecture (plus récent d'abord).
--
-- Aucune entrée existante n'est modifiée (journal en ajout seul) : les entrées sans dépôt
-- (catalogue, utilisateurs, connexions, historique) restent réservées à warehouse:all.
-- =============================================================================

CREATE INDEX ix_audit_log_warehouse_id_created_at ON audit_log (warehouse_id, created_at DESC);
