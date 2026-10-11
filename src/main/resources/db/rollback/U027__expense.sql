-- Annule V027__expense.sql — DÉTRUIT LES DÉPENSES ENREGISTRÉES.
-- Prérequis : aucune écriture de caisse EXPENSE ni EXPENSE_CANCELLATION (sinon les contraintes
-- d'origine de cash_entry ne peuvent pas être rétablies).
UPDATE permission SET description = 'Supprimer des dépenses' WHERE code = 'expense:delete';

ALTER TABLE cash_entry DROP CONSTRAINT ck_cash_entry_expense_cash;
ALTER TABLE cash_entry DROP CONSTRAINT ck_cash_entry_source;
ALTER TABLE cash_entry DROP CONSTRAINT ck_cash_entry_direction;
ALTER TABLE cash_entry DROP CONSTRAINT ck_cash_entry_type;
ALTER TABLE cash_entry ADD CONSTRAINT ck_cash_entry_type CHECK (entry_type IN (
    'SALE', 'CUSTOMER_PAYMENT', 'CASH_IN', 'CORRECTION_IN',
    'SALE_REFUND', 'RETURN_REFUND', 'CASH_OUT', 'CORRECTION_OUT', 'SUPPLIER_PAYMENT'));
ALTER TABLE cash_entry ADD CONSTRAINT ck_cash_entry_direction CHECK (
    (entry_type IN ('SALE', 'CUSTOMER_PAYMENT', 'CASH_IN', 'CORRECTION_IN') AND direction = 'IN')
    OR (entry_type IN ('SALE_REFUND', 'RETURN_REFUND', 'CASH_OUT', 'CORRECTION_OUT', 'SUPPLIER_PAYMENT')
        AND direction = 'OUT'));
ALTER TABLE cash_entry ADD CONSTRAINT ck_cash_entry_source CHECK (
    entry_type NOT IN ('SALE', 'SALE_REFUND', 'RETURN_REFUND', 'CUSTOMER_PAYMENT', 'SUPPLIER_PAYMENT')
    OR (source_type IS NOT NULL AND source_id IS NOT NULL));

DROP TRIGGER trg_expense_cash_consistency ON expense;
DROP FUNCTION fn_expense_cash_consistency();
DROP TRIGGER trg_expense_guard ON expense;
DROP FUNCTION fn_expense_guard();
DROP TABLE expense;
DROP SEQUENCE seq_expense_number;
DELETE FROM flyway_schema_history WHERE version = '027';
