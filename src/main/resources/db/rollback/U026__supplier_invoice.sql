-- Annule V026__supplier_invoice.sql — DÉTRUIT LES FACTURES FOURNISSEURS ENREGISTRÉES.
DROP TRIGGER trg_purchase_order_invoice_guard ON purchase_order;
DROP FUNCTION fn_purchase_order_invoice_guard();
DROP INDEX uk_purchase_order_supplier_invoice;
ALTER TABLE purchase_order DROP CONSTRAINT ck_purchase_order_invoice;
DROP INDEX ix_purchase_order_invoiced_by;
ALTER TABLE purchase_order DROP CONSTRAINT fk_purchase_order_invoiced_by;
ALTER TABLE purchase_order DROP COLUMN invoiced_by;
ALTER TABLE purchase_order DROP COLUMN invoiced_at;
ALTER TABLE purchase_order DROP COLUMN invoiced_amount;
ALTER TABLE purchase_order DROP COLUMN invoice_date;
ALTER TABLE purchase_order DROP COLUMN invoice_number;
DELETE FROM flyway_schema_history WHERE version = '026';
