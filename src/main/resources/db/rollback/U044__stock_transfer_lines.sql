-- Annulation de V044 (lignes de transfert). Chaque transfert reprend le produit de sa première
-- ligne : les autres lignes d'un transfert de plusieurs produits sont perdues (pas les mouvements).

ALTER TABLE stock_transfer
    ADD COLUMN product_id   uuid,
    ADD COLUMN product_sku  varchar(50),
    ADD COLUMN product_name varchar(200),
    ADD COLUMN quantity     integer;

UPDATE stock_transfer t
SET product_id = l.product_id, product_sku = l.product_sku, product_name = l.product_name, quantity = l.quantity
FROM stock_transfer_line l
WHERE l.transfer_id = t.id AND l.line_number = 1;

ALTER TABLE stock_transfer
    ALTER COLUMN product_id SET NOT NULL,
    ALTER COLUMN product_sku SET NOT NULL,
    ALTER COLUMN product_name SET NOT NULL,
    ALTER COLUMN quantity SET NOT NULL,
    ADD CONSTRAINT fk_stock_transfer_product FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_stock_transfer_quantity CHECK (quantity > 0);
CREATE INDEX ix_stock_transfer_product_id ON stock_transfer (product_id);

DROP TABLE IF EXISTS stock_transfer_line;

DELETE FROM flyway_schema_history WHERE version = '044';
