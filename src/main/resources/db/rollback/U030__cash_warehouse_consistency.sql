-- Retour arrière de V030 : la cohérence dépôt de la pièce = dépôt de la caisse n'est plus contrôlée en base.

DROP TRIGGER IF EXISTS trg_cash_entry_warehouse_consistency ON cash_entry;
DROP FUNCTION IF EXISTS fn_cash_entry_warehouse_consistency();

DELETE FROM flyway_schema_history WHERE version = '030';
