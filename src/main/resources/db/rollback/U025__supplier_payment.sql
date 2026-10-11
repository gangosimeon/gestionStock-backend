-- Annule V025__supplier_payment.sql — DÉTRUIT LES PAIEMENTS FOURNISSEURS.
-- Prérequis : aucune écriture de caisse SUPPLIER_PAYMENT (sinon les contraintes d'origine de
-- cash_entry ne peuvent pas être rétablies).
DROP TRIGGER trg_purchase_order_paid_consistency ON purchase_order;
DROP TRIGGER trg_supplier_payment_allocation_paid_consistency ON supplier_payment_allocation;
DROP FUNCTION fn_purchase_order_paid_consistency();
DROP TRIGGER trg_supplier_payment_consistency ON supplier_payment;
DROP FUNCTION fn_supplier_payment_consistency();

ALTER TABLE cash_entry DROP CONSTRAINT ck_cash_entry_supplier_payment_cash;
ALTER TABLE cash_entry DROP CONSTRAINT ck_cash_entry_source;
ALTER TABLE cash_entry DROP CONSTRAINT ck_cash_entry_direction;
ALTER TABLE cash_entry DROP CONSTRAINT ck_cash_entry_type;
ALTER TABLE cash_entry ADD CONSTRAINT ck_cash_entry_type CHECK (entry_type IN (
    'SALE', 'CUSTOMER_PAYMENT', 'CASH_IN', 'CORRECTION_IN', 'SALE_REFUND', 'RETURN_REFUND', 'CASH_OUT', 'CORRECTION_OUT'));
ALTER TABLE cash_entry ADD CONSTRAINT ck_cash_entry_direction CHECK (
    (entry_type IN ('SALE', 'CUSTOMER_PAYMENT', 'CASH_IN', 'CORRECTION_IN') AND direction = 'IN')
    OR (entry_type IN ('SALE_REFUND', 'RETURN_REFUND', 'CASH_OUT', 'CORRECTION_OUT') AND direction = 'OUT'));
ALTER TABLE cash_entry ADD CONSTRAINT ck_cash_entry_source CHECK (
    entry_type NOT IN ('SALE', 'SALE_REFUND', 'RETURN_REFUND', 'CUSTOMER_PAYMENT')
    OR (source_type IS NOT NULL AND source_id IS NOT NULL));

DROP TABLE supplier_payment_allocation;
DROP TABLE supplier_payment;
DROP SEQUENCE seq_supplier_payment_number;

ALTER TABLE purchase_order DROP CONSTRAINT ck_purchase_order_paid_amount;
ALTER TABLE purchase_order DROP COLUMN paid_amount;
DELETE FROM flyway_schema_history WHERE version = '025';
