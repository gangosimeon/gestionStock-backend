-- Retour arrière de V034 : le dépôt du retour n'est plus contrôlé en base contre celui de sa vente.

DROP TRIGGER IF EXISTS trg_customer_return_sale_warehouse ON customer_return;
DROP FUNCTION IF EXISTS fn_customer_return_sale_warehouse();

DELETE FROM flyway_schema_history WHERE version = '034';
