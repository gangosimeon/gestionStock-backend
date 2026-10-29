-- =============================================================================
-- V048 — Génération de l'image des produits (intégration JeCréeMaBoutique, décision P7)
--
-- Compteur durable par produit, source de la version des événements PRODUCT_IMAGE : jamais supprimé avec
-- l'image (une image retirée puis remise obtient une génération supérieure), incrémenté par la première
-- instruction de la transaction d'image. Lu et écrit par le seul module integration. Pas de colonne
-- version : la ligne est elle-même le compteur, verrouillée par son INSERT … ON CONFLICT.
--
-- Remplissage (Q6) : une ligne par image existante, génération 1, empreinte de l'image.
-- Additive : aucune donnée existante n'est modifiée. Annulation : db/rollback/U048__product_image_generation.sql
-- =============================================================================

CREATE TABLE product_image_generation (
    product_id  uuid         NOT NULL,
    generation  bigint       NOT NULL,
    sha256      varchar(64),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_product_image_generation PRIMARY KEY (product_id),
    CONSTRAINT fk_product_image_generation_product FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE CASCADE,
    CONSTRAINT ck_product_image_generation_generation CHECK (generation > 0),
    CONSTRAINT ck_product_image_generation_sha256 CHECK (sha256 IS NULL OR sha256 ~ '^[0-9a-f]{64}$')
);

INSERT INTO product_image_generation (product_id, generation, sha256)
SELECT product_id, 1, sha256 FROM product_image;

COMMENT ON TABLE product_image_generation IS 'Génération de l''image de chaque produit (P7) : version des événements PRODUCT_IMAGE, jamais supprimée avec l''image.';
COMMENT ON COLUMN product_image_generation.generation IS 'Strictement croissante pour un produit ; incrémentée seulement si l''empreinte change (P6).';
COMMENT ON COLUMN product_image_generation.sha256 IS 'Empreinte de l''image courante ; NULL si le produit n''a plus d''image.';
