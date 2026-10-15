-- Retour arrière de V032 : les paiements fournisseurs perdent leur dépôt (montants et imputations intacts).

DROP TRIGGER IF EXISTS trg_supplier_payment_warehouse ON supplier_payment;
DROP FUNCTION IF EXISTS fn_supplier_payment_warehouse();
DROP INDEX IF EXISTS ix_supplier_payment_warehouse_id;
DROP INDEX IF EXISTS ix_supplier_payment_supplier_id_warehouse_id;
ALTER TABLE supplier_payment DROP CONSTRAINT IF EXISTS fk_supplier_payment_warehouse;
ALTER TABLE supplier_payment DROP COLUMN IF EXISTS warehouse_id;

DELETE FROM flyway_schema_history WHERE version = '032';
