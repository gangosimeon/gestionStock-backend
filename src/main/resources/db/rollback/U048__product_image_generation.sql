-- Annulation de V048 : génération de l'image des produits (compteurs supprimés avec la table).
-- DESTRUCTIF (v11-D11) : une ré-application de V048 repart de la génération 1 pour chaque image existante.
-- Une boutique qui a déjà appliqué une génération supérieure ignorerait les changements d'image jusqu'à ce que
-- le compteur la dépasse, et la règle des versions s'applique aussi à l'instantané : un instantané complet ne
-- suffit pas. Après une annulation suivie d'une ré-application, il faut une remise à zéro complète côté
-- JeCréeMaBoutique (oubli des versions locales, puis instantané complet).

DROP TABLE IF EXISTS product_image_generation;

DELETE FROM flyway_schema_history WHERE version = '048';
