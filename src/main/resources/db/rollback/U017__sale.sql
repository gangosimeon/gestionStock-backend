-- Annule V017__sale.sql — DÉTRUIT LES VENTES.
-- Les mouvements de stock (SALE, SALE_CANCELLATION) et les écritures du compte client
-- restent dans leurs journaux respectifs (V013, V016).
DROP TABLE sale_line;
DROP TABLE sale;
DROP FUNCTION fn_sale_consistency();
DROP FUNCTION fn_sale_line_guard();
DROP FUNCTION fn_sale_guard();
DROP SEQUENCE seq_sale_number;
DELETE FROM flyway_schema_history WHERE version = '017';
