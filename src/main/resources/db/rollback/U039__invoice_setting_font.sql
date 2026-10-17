-- Annulation de V039 (police des factures et des devis).

ALTER TABLE invoice_setting DROP CONSTRAINT IF EXISTS ck_invoice_setting_font_family;
ALTER TABLE invoice_setting DROP COLUMN IF EXISTS font_family;

DELETE FROM flyway_schema_history WHERE version = '039';
