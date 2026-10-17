-- Annulation de V037 (quantité par bénéficiaire des lignes de devis). Les quantités totales sont conservées.

ALTER TABLE quote_line DROP CONSTRAINT IF EXISTS ck_quote_line_quantity_per_beneficiary;
ALTER TABLE quote_line DROP COLUMN IF EXISTS quantity_per_beneficiary;

DELETE FROM flyway_schema_history WHERE version = '037';
