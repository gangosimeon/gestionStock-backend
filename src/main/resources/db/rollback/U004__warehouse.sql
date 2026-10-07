-- Annule V004__warehouse.sql — DÉTRUIT LES DÉPÔTS. Prérequis : U005 déjà exécuté.
DROP TABLE warehouse;
DELETE FROM flyway_schema_history WHERE version = '004';
