-- Annule V010__catalog_category_brand.sql — DÉTRUIT CATÉGORIES ET MARQUES. Prérequis : U011.
DROP TABLE brand;
DROP TABLE category;
DELETE FROM flyway_schema_history WHERE version = '010';
