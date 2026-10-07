-- Annule V006__audit_log.sql — DÉTRUIT LE JOURNAL D'AUDIT.
-- Exécution : psql -v ON_ERROR_STOP=1 -1 -f U006__audit_log.sql (voir docs/persistence.md §8)
DROP TABLE audit_log;  -- supprime aussi ses triggers et index
DELETE FROM flyway_schema_history WHERE version = '006';
