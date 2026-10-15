-- Retour arrière de V033 : le journal d'audit perd son index par dépôt (aucune donnée touchée).

DROP INDEX IF EXISTS ix_audit_log_warehouse_id_created_at;

DELETE FROM flyway_schema_history WHERE version = '033';
