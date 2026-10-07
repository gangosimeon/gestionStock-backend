-- Annule V011__catalog_product.sql — DÉTRUIT LE CATALOGUE PRODUITS.
-- Prérequis : aucune table ne référence encore product (modules stock, ventes...).
DROP TABLE product;
DELETE FROM flyway_schema_history WHERE version = '011';
