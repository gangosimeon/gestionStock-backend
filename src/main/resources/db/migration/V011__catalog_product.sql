-- =============================================================================
-- V011 — Catalogue : produits
--
-- Le produit NE porte PAS de stock : les quantités vivent dans le module stock
-- (niveaux par dépôt + mouvements), seule source de vérité. Le champ stockQuantity
-- de l'API est calculé à la lecture.
--
-- default_supplier_id : fournisseur habituel (contrat front supplierId). La clé
-- étrangère sera ajoutée par la migration du module fournisseur, qui n'existe pas
-- encore (elle mettra à NULL toute valeur sans fournisseur correspondant).
--
-- Montants en numeric(14,2) (FCFA). purchase_price = dernier prix d'achat de
-- référence ; le coût moyen pondéré (CUMP) sera géré par les modules achats/stock.
--
-- Annulation : db/rollback/U011__catalog_product.sql
-- =============================================================================

CREATE TABLE product (
    id                  uuid          NOT NULL DEFAULT gen_random_uuid(),
    sku                 varchar(50)   NOT NULL,
    barcode             varchar(14),
    name                varchar(200)  NOT NULL,
    description         varchar(1000),
    category_id         uuid          NOT NULL,
    brand_id            uuid,
    default_supplier_id uuid,
    unit                varchar(20)   NOT NULL DEFAULT 'UNIT',
    purchase_price      numeric(14,2) NOT NULL DEFAULT 0,
    retail_price        numeric(14,2) NOT NULL DEFAULT 0,
    wholesale_price     numeric(14,2) NOT NULL DEFAULT 0,
    alert_threshold     integer       NOT NULL DEFAULT 0,
    max_stock_level     integer,
    active              boolean       NOT NULL DEFAULT true,
    version             bigint        NOT NULL DEFAULT 0,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    created_by          uuid,
    updated_at          timestamptz   NOT NULL DEFAULT now(),
    updated_by          uuid,
    CONSTRAINT pk_product PRIMARY KEY (id),
    CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES category (id) ON DELETE RESTRICT,
    CONSTRAINT fk_product_brand FOREIGN KEY (brand_id) REFERENCES brand (id) ON DELETE RESTRICT,
    CONSTRAINT ck_product_sku_format CHECK (sku ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,49}$'),
    CONSTRAINT ck_product_barcode_format CHECK (barcode IS NULL OR barcode ~ '^[0-9]{8,14}$'),
    CONSTRAINT ck_product_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_product_unit CHECK (unit IN ('UNIT', 'PAIR', 'BOX', 'BOTTLE')),
    CONSTRAINT ck_product_prices CHECK (purchase_price >= 0 AND retail_price >= 0 AND wholesale_price >= 0),
    CONSTRAINT ck_product_alert_threshold CHECK (alert_threshold >= 0),
    CONSTRAINT ck_product_max_stock_level CHECK (max_stock_level IS NULL OR max_stock_level >= alert_threshold)
);

-- Unicité insensible à la casse ; code-barres unique s'il est renseigné.
CREATE UNIQUE INDEX uk_product_sku ON product (lower(sku));
CREATE UNIQUE INDEX uk_product_barcode ON product (barcode) WHERE barcode IS NOT NULL;

-- Filtres et tris de la liste.
CREATE INDEX ix_product_category_id ON product (category_id);
CREATE INDEX ix_product_brand_id ON product (brand_id);
CREATE INDEX ix_product_default_supplier_id ON product (default_supplier_id);
CREATE INDEX ix_product_name ON product (lower(name));

COMMENT ON TABLE product IS 'Produits du catalogue. Aucun stock ici : voir le module stock.';
COMMENT ON COLUMN product.unit IS 'Unité de vente : UNIT (pièce), PAIR (paire de verres), BOX (boîte de lentilles), BOTTLE (flacon).';
COMMENT ON COLUMN product.default_supplier_id IS 'Fournisseur habituel. Clé étrangère ajoutée avec le module fournisseur.';
COMMENT ON COLUMN product.alert_threshold IS 'Seuil d''alerte stock bas (comparé au niveau de chaque dépôt).';
COMMENT ON COLUMN product.max_stock_level IS 'Niveau au-delà duquel le stock est considéré excédentaire (facultatif).';
