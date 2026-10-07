-- Annule V013__stock.sql — DÉTRUIT TOUT LE STOCK ET SON HISTORIQUE.
-- Prérequis : aucune table ne référence encore stock_movement (achats, ventes, inventaire...).
DROP TABLE stock_level;
DROP TABLE stock_movement;
DROP FUNCTION fn_stock_level_guard();
DELETE FROM flyway_schema_history WHERE version = '013';
