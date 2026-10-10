-- Annule V018__cash.sql — DÉTRUIT LES SESSIONS ET ÉCRITURES DE CAISSE.
-- Les ventes et le compte client restent (leurs encaissements disparaissent avec la caisse).
DROP TRIGGER trg_customer_account_entry_payment_consistency ON customer_account_entry;
DROP TRIGGER trg_sale_cash_consistency ON sale;
DROP FUNCTION fn_sale_cash_consistency();
DROP TABLE cash_entry;
DROP FUNCTION fn_customer_payment_consistency();
DROP TABLE cash_session;
DROP FUNCTION fn_cash_session_guard();
DROP FUNCTION fn_cash_entry_guard();
DROP SEQUENCE seq_cash_entry_number;
DROP SEQUENCE seq_cash_session_number;
DELETE FROM flyway_schema_history WHERE version = '018';
