-- Retour arrière de V023 (index du tableau de bord).

DROP INDEX IF EXISTS ix_goods_receipt_received_at;
DROP INDEX IF EXISTS ix_customer_return_created_at;
DROP INDEX IF EXISTS ix_stock_movement_created_at;
DROP INDEX IF EXISTS ix_sale_completed_created_at;

DELETE FROM flyway_schema_history WHERE version = '023';
