-- =============================================================================
-- V010 — Catalogue : catégories et marques
--
-- Catégories : données de référence du magasin d'optique, reprises du front (le front
-- ne propose pas d'écran de gestion des catégories : elles doivent exister en
-- production). Modifiables ensuite par l'API (permission category:manage).
-- Marques : nouvelle entité (la marque n'apparaissait que dans le libellé des produits).
--
-- Désactivation plutôt que suppression (active = false).
--
-- Annulation : db/rollback/U010__catalog_category_brand.sql
-- =============================================================================

CREATE TABLE category (
    id          uuid         NOT NULL DEFAULT gen_random_uuid(),
    name        varchar(100) NOT NULL,
    description varchar(255),
    active      boolean      NOT NULL DEFAULT true,
    version     bigint       NOT NULL DEFAULT 0,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    created_by  uuid,
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    updated_by  uuid,
    CONSTRAINT pk_category PRIMARY KEY (id),
    CONSTRAINT ck_category_name_not_blank CHECK (btrim(name) <> '')
);

CREATE UNIQUE INDEX uk_category_name ON category (lower(name));

COMMENT ON TABLE category IS 'Catégories de produits (montures, verres, lentilles...).';

CREATE TABLE brand (
    id         uuid         NOT NULL DEFAULT gen_random_uuid(),
    name       varchar(100) NOT NULL,
    active     boolean      NOT NULL DEFAULT true,
    version    bigint       NOT NULL DEFAULT 0,
    created_at timestamptz  NOT NULL DEFAULT now(),
    created_by uuid,
    updated_at timestamptz  NOT NULL DEFAULT now(),
    updated_by uuid,
    CONSTRAINT pk_brand PRIMARY KEY (id),
    CONSTRAINT ck_brand_name_not_blank CHECK (btrim(name) <> '')
);

CREATE UNIQUE INDEX uk_brand_name ON brand (lower(name));

COMMENT ON TABLE brand IS 'Marques des produits (Ray-Ban, Essilor, CooperVision...).';

INSERT INTO category (name, description) VALUES
    ('Montures',              'Montures optiques et solaires'),
    ('Verres correcteurs',    'Verres unifocaux, progressifs et traitements'),
    ('Lentilles de contact',  'Lentilles journalières, mensuelles et colorées'),
    ('Accessoires',           'Étuis, cordons, chiffonnettes'),
    ('Solutions d''entretien', 'Solutions et produits d''entretien des lentilles et lunettes');
