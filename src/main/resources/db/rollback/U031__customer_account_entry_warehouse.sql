-- Retour arrière de V031 : les écritures du compte client perdent leur dépôt (montants et soldes intacts).

DROP TRIGGER IF EXISTS trg_customer_account_entry_warehouse ON customer_account_entry;
DROP FUNCTION IF EXISTS fn_customer_account_entry_warehouse();
DROP INDEX IF EXISTS ix_customer_account_entry_warehouse_id;
DROP INDEX IF EXISTS ix_customer_account_entry_customer_id_warehouse_id;
ALTER TABLE customer_account_entry DROP CONSTRAINT IF EXISTS fk_customer_account_entry_warehouse;
ALTER TABLE customer_account_entry DROP COLUMN IF EXISTS warehouse_id;

DELETE FROM flyway_schema_history WHERE version = '031';
