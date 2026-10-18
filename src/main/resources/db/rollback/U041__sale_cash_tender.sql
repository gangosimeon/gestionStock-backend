-- Annulation de V041 (montant reçu et monnaie rendue des ventes). Ces informations sont perdues.

DROP TRIGGER IF EXISTS trg_sale_cash_tender_guard ON sale;
DROP FUNCTION IF EXISTS fn_sale_cash_tender_guard();
ALTER TABLE sale DROP CONSTRAINT IF EXISTS ck_sale_cash_tender;
ALTER TABLE sale DROP COLUMN IF EXISTS change_given;
ALTER TABLE sale DROP COLUMN IF EXISTS cash_received;

DELETE FROM flyway_schema_history WHERE version = '041';
