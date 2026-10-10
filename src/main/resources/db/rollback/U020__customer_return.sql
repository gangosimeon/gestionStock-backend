-- Annule V020__customer_return.sql — DÉTRUIT LES RETOURS CLIENTS.
-- Prérequis : aucune écriture de caisse RETURN_REFUND ni CORRECTION_* (sinon les contraintes
-- d'origine de cash_entry ne peuvent pas être rétablies).
DROP TRIGGER trg_sale_return_guard ON sale;
DROP FUNCTION fn_sale_return_guard();
DROP TABLE customer_return_line;
DROP TABLE customer_return;
DROP FUNCTION fn_customer_return_consistency();
DROP FUNCTION fn_customer_return_line_guard();
DROP SEQUENCE seq_customer_return_number;

DROP TRIGGER trg_cash_entry_correction_guard ON cash_entry;
DROP FUNCTION fn_cash_correction_guard();
ALTER TABLE cash_entry DROP CONSTRAINT ck_cash_entry_correction;
ALTER TABLE cash_entry DROP CONSTRAINT ck_cash_entry_source;
ALTER TABLE cash_entry DROP CONSTRAINT ck_cash_entry_manual_cash;
ALTER TABLE cash_entry DROP CONSTRAINT ck_cash_entry_direction;
ALTER TABLE cash_entry DROP CONSTRAINT ck_cash_entry_type;
ALTER TABLE cash_entry ADD CONSTRAINT ck_cash_entry_type CHECK (entry_type IN ('SALE', 'CUSTOMER_PAYMENT', 'CASH_IN', 'SALE_REFUND', 'CASH_OUT'));
ALTER TABLE cash_entry ADD CONSTRAINT ck_cash_entry_direction CHECK (
    (entry_type IN ('SALE', 'CUSTOMER_PAYMENT', 'CASH_IN') AND direction = 'IN')
    OR (entry_type IN ('SALE_REFUND', 'CASH_OUT') AND direction = 'OUT'));
ALTER TABLE cash_entry ADD CONSTRAINT ck_cash_entry_manual_cash CHECK (entry_type NOT IN ('CASH_IN', 'CASH_OUT') OR payment_method = 'CASH');
ALTER TABLE cash_entry ADD CONSTRAINT ck_cash_entry_source CHECK (entry_type NOT IN ('SALE', 'SALE_REFUND', 'CUSTOMER_PAYMENT')
                                       OR (source_type IS NOT NULL AND source_id IS NOT NULL));
DROP INDEX uk_cash_entry_reversal_of_id;
ALTER TABLE cash_entry DROP CONSTRAINT fk_cash_entry_reversal_of;
ALTER TABLE cash_entry DROP COLUMN reversal_of_id;
DELETE FROM flyway_schema_history WHERE version = '020';
