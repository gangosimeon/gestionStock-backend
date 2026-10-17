-- Annulation de V040 (nom complet du signataire).

ALTER TABLE invoice_setting DROP COLUMN IF EXISTS signature_name;

DELETE FROM flyway_schema_history WHERE version = '040';
