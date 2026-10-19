-- =============================================================================
-- V043 — Transferts de stock entre dépôts : pièce numérotée et retour
--
-- Jusqu'ici un transfert n'existait que par ses deux mouvements (TRANSFER_OUT au dépôt
-- d'origine, TRANSFER_IN au dépôt de destination, même correlation_id). stock_transfer en
-- fait une pièce : numéro TRF-AAAA-nnnnnn (reçu), dépôts, produit et noms recopiés (le reçu
-- ne change pas si un dépôt ou un produit est renommé), auteur, statut.
--
-- La quantité en stock reste portée par stock_level et ses mouvements : cette table n'en
-- modifie aucune. Les mouvements du transfert la désignent par source_id (source_type
-- TRANSFER) et portent son numéro dans source_reference.
--
-- Retour : le stock transféré revient au dépôt d'origine, en totalité et une seule fois
-- (statut RETURNED), par deux nouveaux mouvements (TRANSFER_OUT à la destination,
-- TRANSFER_IN à l'origine) rattachés à la même pièce. Les mouvements d'origine restent dans
-- l'historique.
--
-- Reprise : les transferts déjà enregistrés (paires de mouvements) deviennent des pièces,
-- numérotées dans l'ordre chronologique ; l'identifiant de la pièce est leur source_id.
--
-- Annulation : db/rollback/U043__stock_transfer.sql
-- =============================================================================

CREATE SEQUENCE seq_stock_transfer_number;

CREATE TABLE stock_transfer (
    id                  uuid          NOT NULL DEFAULT gen_random_uuid(),
    number              varchar(30)   NOT NULL,
    from_warehouse_id   uuid          NOT NULL,
    from_warehouse_name varchar(100)  NOT NULL,
    to_warehouse_id     uuid          NOT NULL,
    to_warehouse_name   varchar(100)  NOT NULL,
    product_id          uuid          NOT NULL,
    product_sku         varchar(50)   NOT NULL,
    product_name        varchar(200)  NOT NULL,
    quantity            integer       NOT NULL,
    note                varchar(500),
    status              varchar(20)   NOT NULL DEFAULT 'COMPLETED',
    returned_at         timestamptz,
    returned_by         uuid,
    return_reason       varchar(500),
    version             bigint        NOT NULL DEFAULT 0,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    created_by          uuid,
    updated_at          timestamptz   NOT NULL DEFAULT now(),
    updated_by          uuid,
    CONSTRAINT pk_stock_transfer PRIMARY KEY (id),
    CONSTRAINT fk_stock_transfer_from_warehouse FOREIGN KEY (from_warehouse_id)
        REFERENCES warehouse (id) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_transfer_to_warehouse FOREIGN KEY (to_warehouse_id)
        REFERENCES warehouse (id) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_transfer_product FOREIGN KEY (product_id)
        REFERENCES product (id) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_transfer_returned_by FOREIGN KEY (returned_by)
        REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_stock_transfer_status CHECK (status IN ('COMPLETED', 'RETURNED')),
    CONSTRAINT ck_stock_transfer_quantity CHECK (quantity > 0),
    CONSTRAINT ck_stock_transfer_warehouses CHECK (from_warehouse_id <> to_warehouse_id),
    -- Retourné ⇔ date et auteur du retour.
    CONSTRAINT ck_stock_transfer_returned CHECK ((status = 'RETURNED') = (returned_at IS NOT NULL)
                                                 AND (returned_at IS NULL) = (returned_by IS NULL))
);

CREATE UNIQUE INDEX uk_stock_transfer_number ON stock_transfer (number);
-- Historique d'un dépôt : transferts envoyés et reçus, du plus récent au plus ancien.
CREATE INDEX ix_stock_transfer_from_created_at ON stock_transfer (from_warehouse_id, created_at DESC);
CREATE INDEX ix_stock_transfer_to_created_at ON stock_transfer (to_warehouse_id, created_at DESC);
CREATE INDEX ix_stock_transfer_product_id ON stock_transfer (product_id);
CREATE INDEX ix_stock_transfer_returned_by ON stock_transfer (returned_by);

COMMENT ON TABLE stock_transfer IS 'Transfert de stock entre deux dépôts (pièce du reçu) ; le stock est porté par ses mouvements.';
COMMENT ON COLUMN stock_transfer.number IS 'Numéro lisible TRF-AAAA-nnnnnn (séquence seq_stock_transfer_number).';
COMMENT ON COLUMN stock_transfer.from_warehouse_name IS 'Nom du dépôt d''origine à la date du transfert (instantané).';
COMMENT ON COLUMN stock_transfer.to_warehouse_name IS 'Nom du dépôt de destination à la date du transfert (instantané).';
COMMENT ON COLUMN stock_transfer.status IS 'COMPLETED (effectué) ou RETURNED (stock revenu au dépôt d''origine).';

-- --- Reprise des transferts existants ---------------------------------------------------------

INSERT INTO stock_transfer (id, number, from_warehouse_id, from_warehouse_name, to_warehouse_id, to_warehouse_name,
                            product_id, product_sku, product_name, quantity, note, created_at, created_by,
                            updated_at, updated_by)
SELECT t.id,
       'TRF-' || to_char(t.created_at AT TIME ZONE 'UTC', 'YYYY') || '-'
           || lpad(nextval('seq_stock_transfer_number')::text, 6, '0'),
       t.from_warehouse_id, t.from_warehouse_name, t.to_warehouse_id, t.to_warehouse_name,
       t.product_id, t.product_sku, t.product_name, t.quantity, t.note, t.created_at, t.created_by,
       t.created_at, t.created_by
FROM (
    SELECT o.source_id AS id, o.warehouse_id AS from_warehouse_id, fw.name AS from_warehouse_name,
           i.warehouse_id AS to_warehouse_id, tw.name AS to_warehouse_name,
           o.product_id, p.sku AS product_sku, p.name AS product_name, -o.quantity AS quantity,
           o.reason AS note, o.created_at, o.created_by
    FROM stock_movement o
    JOIN stock_movement i ON i.correlation_id = o.correlation_id AND i.type = 'TRANSFER_IN'
                          AND i.source_type = 'TRANSFER'
    JOIN warehouse fw ON fw.id = o.warehouse_id
    JOIN warehouse tw ON tw.id = i.warehouse_id
    JOIN product p ON p.id = o.product_id
    WHERE o.type = 'TRANSFER_OUT' AND o.source_type = 'TRANSFER' AND o.source_id IS NOT NULL
    ORDER BY o.created_at, o.id
) t;
