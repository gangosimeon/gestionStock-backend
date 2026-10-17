-- Annulation de V038 (alignement des en-têtes du tableau des produits).

ALTER TABLE invoice_setting DROP CONSTRAINT IF EXISTS ck_invoice_setting_table_header_alignment;
ALTER TABLE invoice_setting DROP COLUMN IF EXISTS table_header_alignment;

DELETE FROM flyway_schema_history WHERE version = '038';
