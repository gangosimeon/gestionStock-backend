-- Annule V001__core_functions.sql. Prérequis : plus aucune table journal (U006).
DROP FUNCTION fn_prevent_modification();
DELETE FROM flyway_schema_history WHERE version = '001';
