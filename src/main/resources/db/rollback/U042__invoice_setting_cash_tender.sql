-- Annulation de V042 (affichage du montant reçu et de la monnaie rendue sur la facture).

ALTER TABLE invoice_setting DROP COLUMN IF EXISTS show_cash_tender;

DELETE FROM flyway_schema_history WHERE version = '042';
