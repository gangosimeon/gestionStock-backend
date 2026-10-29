-- Annulation de V048 : génération de l'image des produits (compteurs supprimés avec la table).
-- Attention : une ré-application de V048 repart de la génération 1 pour chaque image existante. Une boutique
-- qui a déjà appliqué une génération supérieure ignorerait les changements suivants jusqu'à la dépasser :
-- après une annulation suivie d'une ré-application, relancer un instantané complet côté boutique.

DROP TABLE IF EXISTS product_image_generation;

DELETE FROM flyway_schema_history WHERE version = '048';
