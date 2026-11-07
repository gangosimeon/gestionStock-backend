-- Annulation de V056 : suppression des index de clés étrangères sur product_id.
-- Pour les tests de retour arrière : jamais en production.

DROP INDEX IF EXISTS ix_online_order_line_product;
DROP INDEX IF EXISTS ix_online_reservation_line_product;

DELETE FROM flyway_schema_history WHERE version = '056';
