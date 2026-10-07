-- Annule V012__warehouse_main_flag.sql.
ALTER TABLE warehouse DROP CONSTRAINT ck_warehouse_main_active;
DROP INDEX uk_warehouse_main;
ALTER TABLE warehouse DROP COLUMN main;
DELETE FROM flyway_schema_history WHERE version = '012';
