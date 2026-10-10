-- Annule V019__inventory.sql — DÉTRUIT LES INVENTAIRES.
-- Les mouvements de stock INVENTORY déjà appliqués restent dans le journal du stock (V013).
DROP TABLE inventory_line;
DROP TABLE inventory_session;
DROP FUNCTION fn_inventory_consistency();
DROP FUNCTION fn_inventory_line_guard();
DROP FUNCTION fn_inventory_session_guard();
DROP SEQUENCE seq_inventory_number;
DELETE FROM flyway_schema_history WHERE version = '019';
