-- Annulation de V043 (pièces de transfert). Les mouvements de stock ne sont pas touchés : les
-- transferts redeviennent de simples paires de mouvements.

DROP TABLE IF EXISTS stock_transfer;
DROP SEQUENCE IF EXISTS seq_stock_transfer_number;

DELETE FROM flyway_schema_history WHERE version = '043';
