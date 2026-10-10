-- Annule V015__purchase.sql — DÉTRUIT COMMANDES FOURNISSEURS ET RÉCEPTIONS.
-- Les mouvements de stock RECEIPT des réceptions restent dans le journal du stock (V013).
DROP TABLE goods_receipt_line;
DROP TABLE goods_receipt;
DROP TABLE purchase_order_line;
DROP TABLE purchase_order;
DROP FUNCTION fn_purchase_receipt_consistency();
DROP FUNCTION fn_goods_receipt_line_guard();
DROP SEQUENCE seq_goods_receipt_number;
DROP SEQUENCE seq_purchase_order_number;
DELETE FROM flyway_schema_history WHERE version = '015';
