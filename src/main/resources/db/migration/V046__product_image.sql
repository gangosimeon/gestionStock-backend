-- =============================================================================
-- V046 — Photo facultative d'un produit
--
-- Au plus une photo par produit, stockée en base comme le logo des factures (isolée par entreprise,
-- incluse dans les sauvegardes). Le serveur la réduit et la ré-encode toujours en JPEG (512 px de
-- côté au plus, sans métadonnées) : quelques dizaines de Ko par photo, quelle que soit l'image envoyée.
-- Table séparée du produit : les listes de produits restent légères, et changer la photo ne modifie
-- pas le produit (aucun conflit avec une fiche en cours de modification).
--
-- Additive : aucune donnée existante n'est modifiée. Annulation : db/rollback/U046__product_image.sql
-- =============================================================================

CREATE TABLE product_image (
    id           uuid         NOT NULL DEFAULT gen_random_uuid(),
    product_id   uuid         NOT NULL,
    content_type varchar(20)  NOT NULL,
    data         bytea        NOT NULL,
    size_bytes   integer      NOT NULL,
    width        integer      NOT NULL,
    height       integer      NOT NULL,
    sha256       varchar(64)  NOT NULL,
    version      bigint       NOT NULL DEFAULT 0,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    created_by   uuid,
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    updated_by   uuid,
    CONSTRAINT pk_product_image PRIMARY KEY (id),
    CONSTRAINT fk_product_image_product FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE CASCADE,
    CONSTRAINT ck_product_image_content_type CHECK (content_type = 'image/jpeg'),
    CONSTRAINT ck_product_image_size CHECK (size_bytes BETWEEN 1 AND 524288 AND octet_length(data) = size_bytes),
    CONSTRAINT ck_product_image_dimensions CHECK (width BETWEEN 1 AND 1024 AND height BETWEEN 1 AND 1024),
    CONSTRAINT ck_product_image_sha256 CHECK (sha256 ~ '^[0-9a-f]{64}$')
);

-- Une photo par produit (et index de la clé étrangère).
CREATE UNIQUE INDEX uk_product_image_product ON product_image (product_id);

COMMENT ON TABLE product_image IS 'Photo facultative d''un produit (au plus une) : JPEG ré-encodé par le serveur, 512 px de côté au plus.';
COMMENT ON COLUMN product_image.sha256 IS 'Empreinte de l''image : version servie au navigateur (ETag, cache).';
