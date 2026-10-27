-- Annulation de V046 : photos des produits (supprimées avec la table).

DROP TABLE IF EXISTS product_image;

DELETE FROM flyway_schema_history WHERE version = '046';
