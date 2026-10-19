-- =============================================================================
-- V044 — Transferts de plusieurs produits
--
-- Un transfert (V043) ne portait qu'un produit. Il porte désormais des lignes, comme une vente :
-- stock_transfer_line (produit, référence et libellé recopiés, quantité), un produit au plus
-- une fois par transfert. L'en-tête garde les dépôts, la note, l'auteur et le statut ; le retour
-- au dépôt d'origine porte sur toutes les lignes.
--
-- Reprise : chaque transfert existant devient un transfert d'une ligne (n° 1), puis les colonnes
-- produit de l'en-tête sont supprimées.
--
-- Annulation : db/rollback/U044__stock_transfer_lines.sql
-- =============================================================================

CREATE TABLE stock_transfer_line (
    id           uuid          NOT NULL DEFAULT gen_random_uuid(),
    transfer_id  uuid          NOT NULL,
    line_number  integer       NOT NULL,
    product_id   uuid          NOT NULL,
    product_sku  varchar(50)   NOT NULL,
    product_name varchar(200)  NOT NULL,
    quantity     integer       NOT NULL,
    CONSTRAINT pk_stock_transfer_line PRIMARY KEY (id),
    CONSTRAINT fk_stock_transfer_line_transfer FOREIGN KEY (transfer_id)
        REFERENCES stock_transfer (id) ON DELETE CASCADE,
    CONSTRAINT fk_stock_transfer_line_product FOREIGN KEY (product_id)
        REFERENCES product (id) ON DELETE RESTRICT,
    CONSTRAINT ck_stock_transfer_line_line_number CHECK (line_number > 0),
    CONSTRAINT ck_stock_transfer_line_quantity CHECK (quantity > 0)
);

CREATE UNIQUE INDEX uk_stock_transfer_line_number ON stock_transfer_line (transfer_id, line_number);
CREATE UNIQUE INDEX uk_stock_transfer_line_product ON stock_transfer_line (transfer_id, product_id);
CREATE INDEX ix_stock_transfer_line_product_id ON stock_transfer_line (product_id);

COMMENT ON TABLE stock_transfer_line IS 'Lignes d''un transfert : produit (référence et libellé recopiés) et quantité transférée.';

INSERT INTO stock_transfer_line (transfer_id, line_number, product_id, product_sku, product_name, quantity)
SELECT id, 1, product_id, product_sku, product_name, quantity FROM stock_transfer;

-- La clé étrangère, la contrainte de quantité et l'index produit partent avec leurs colonnes.
ALTER TABLE stock_transfer
    DROP COLUMN product_id,
    DROP COLUMN product_sku,
    DROP COLUMN product_name,
    DROP COLUMN quantity;
