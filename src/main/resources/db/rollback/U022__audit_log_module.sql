-- Retour arrière de V022 (module du journal d'audit).

DROP INDEX IF EXISTS ix_audit_log_status_created_at;
DROP INDEX IF EXISTS ix_audit_log_module_created_at;
ALTER TABLE audit_log DROP CONSTRAINT IF EXISTS ck_audit_log_module_format;
ALTER TABLE audit_log DROP COLUMN IF EXISTS module;
COMMENT ON COLUMN audit_log.user_id IS NULL;
COMMENT ON COLUMN audit_log.meta IS NULL;

DELETE FROM flyway_schema_history WHERE version = '022';
