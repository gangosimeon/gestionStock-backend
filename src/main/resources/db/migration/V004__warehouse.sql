-- =============================================================================
-- V004 — Dépôts et magasins
--
-- Toute pièce (vente, mouvement de stock, inventaire, session de caisse) sera
-- rattachée à un dépôt. Un dépôt principal est créé : l'application ne peut pas
-- fonctionner sans au moins un dépôt.
--
-- Soft delete : active = false (une FK RESTRICT empêche toute suppression physique
-- d'un dépôt référencé).
--
-- Annulation : db/rollback/U004__warehouse.sql
-- =============================================================================

CREATE TABLE warehouse (
    id         uuid         NOT NULL DEFAULT gen_random_uuid(),
    code       varchar(20)  NOT NULL,
    name       varchar(100) NOT NULL,
    type       varchar(20)  NOT NULL DEFAULT 'STORE',
    address    varchar(255),
    phone      varchar(30),
    active     boolean      NOT NULL DEFAULT true,
    version    bigint       NOT NULL DEFAULT 0,
    created_at timestamptz  NOT NULL DEFAULT now(),
    created_by uuid,
    updated_at timestamptz  NOT NULL DEFAULT now(),
    updated_by uuid,
    CONSTRAINT pk_warehouse PRIMARY KEY (id),
    CONSTRAINT uk_warehouse_code UNIQUE (code),
    CONSTRAINT ck_warehouse_code_format CHECK (code ~ '^[A-Z0-9][A-Z0-9-]*$'),
    CONSTRAINT ck_warehouse_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_warehouse_type CHECK (type IN ('STORE', 'WAREHOUSE'))
);

-- Nom unique, insensible à la casse (règle RG-WH-1).
CREATE UNIQUE INDEX uk_warehouse_name ON warehouse (lower(name));

COMMENT ON TABLE warehouse IS 'Dépôts et magasins. Toute pièce de stock ou de vente y est rattachée.';
COMMENT ON COLUMN warehouse.type IS 'STORE = point de vente ; WAREHOUSE = dépôt de stockage sans vente.';

INSERT INTO warehouse (code, name, type)
VALUES ('MAG-01', 'Magasin principal', 'STORE');
