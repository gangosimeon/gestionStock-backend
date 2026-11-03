-- =============================================================================
-- V054 — Prix proposés par les boutiques en ligne (intégration JeCréeMaBoutique, lot 3, étape G22a ; D2, C6, v16-D3)
--
-- channel_price : une ligne par proposition d'une liaison pour un produit (POST /prices/proposals). Le contenu
-- (prix, devise, version et prix de référence vus) ne change jamais ; seul le statut avance : PROPOSED, puis
-- APPROVED ou REJECTED (motif obligatoire), SUPERSEDED (remplacée par une proposition plus récente), WITHDRAWN
-- (réservé). Au plus une proposition PROPOSED par (liaison, produit) : index unique partiel (C6).
-- channel_price_head : une ligne par (liaison, produit), verrouillée (FOR UPDATE) par chaque proposition : les
-- propositions concurrentes sont sérialisées, la dernière qui obtient le verrou est retenue (C6). Porte le compteur
-- price_version des propositions du couple.
-- integration_idempotency : réponse enregistrée d'une écriture de la boutique par (liaison, opération, clé
-- Idempotency-Key) ; une requête rejouée renvoie la même réponse. Jamais purgée (plan § 36, P3).
--
-- Aucune clé étrangère vers la base plateforme (link_id : autre base), comme integration_link_ack.link_id.
-- Additive. Annulation : db/rollback/U054__channel_price.sql (tests seulement, jamais en production).
-- =============================================================================

CREATE TABLE channel_price (
    id                         uuid          NOT NULL,
    link_id                    uuid          NOT NULL,
    product_id                 uuid          NOT NULL,
    price_version              bigint        NOT NULL,
    status                     varchar(12)   NOT NULL,
    price                      numeric(14,2) NOT NULL,
    currency                   char(3)       NOT NULL,
    based_on_reference_version bigint        NOT NULL,
    reference_price            numeric(14,2) NOT NULL,
    note                       varchar(500),
    proposed_at                timestamptz   NOT NULL DEFAULT now(),
    decided_at                 timestamptz,
    decided_by                 uuid,
    rejection_reason           varchar(500),
    approved_reference_price   numeric(14,2),
    CONSTRAINT pk_channel_price PRIMARY KEY (id),
    CONSTRAINT fk_channel_price_product FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE RESTRICT,
    CONSTRAINT ck_channel_price_status CHECK (status IN ('PROPOSED', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'SUPERSEDED')),
    CONSTRAINT ck_channel_price_price CHECK (price > 0),
    CONSTRAINT ck_channel_price_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_channel_price_versions CHECK (price_version >= 1 AND based_on_reference_version >= 0),
    CONSTRAINT ck_channel_price_reference_price CHECK (reference_price >= 0
        AND (approved_reference_price IS NULL OR approved_reference_price >= 0)),
    -- Décidée (date) si et seulement si elle n'est plus PROPOSED ; motif si et seulement si REJECTED ;
    -- prix de référence de l'approbation si et seulement si APPROVED.
    CONSTRAINT ck_channel_price_decided CHECK ((status = 'PROPOSED') = (decided_at IS NULL)),
    CONSTRAINT ck_channel_price_rejection CHECK ((status = 'REJECTED') = (rejection_reason IS NOT NULL)
        AND (rejection_reason IS NULL OR btrim(rejection_reason) <> '')),
    CONSTRAINT ck_channel_price_approved CHECK ((status = 'APPROVED') = (approved_reference_price IS NOT NULL))
);

-- C6 : une seule proposition en attente par (liaison, produit).
CREATE UNIQUE INDEX uk_channel_price_proposed ON channel_price (link_id, product_id) WHERE status = 'PROPOSED';
-- Lecture des prix d'une liaison (dernière proposition, dernier prix approuvé).
CREATE INDEX ix_channel_price_link_product ON channel_price (link_id, product_id, price_version);
CREATE INDEX ix_channel_price_product_id ON channel_price (product_id);

COMMENT ON TABLE channel_price IS 'Propositions de prix des boutiques liées (D2) : contenu immuable, seul le statut avance ; au plus une PROPOSED par (liaison, produit) (C6).';
COMMENT ON COLUMN channel_price.link_id IS 'Liaison (platform.integration_link.id, autre base : sans clé étrangère).';
COMMENT ON COLUMN channel_price.price_version IS 'Rang de la proposition pour (liaison, produit), attribué sous le verrou de channel_price_head.';
COMMENT ON COLUMN channel_price.status IS 'PROPOSED, APPROVED, REJECTED (motif), SUPERSEDED (remplacée), WITHDRAWN (réservé).';
COMMENT ON COLUMN channel_price.based_on_reference_version IS 'Version du produit (prix de référence) vue par la boutique ; égale à product.version à la proposition.';
COMMENT ON COLUMN channel_price.reference_price IS 'Prix de référence (product.retail_price) au moment de la proposition.';
COMMENT ON COLUMN channel_price.decided_by IS 'Utilisateur GestStock qui a décidé (approbation ou refus).';
COMMENT ON COLUMN channel_price.approved_reference_price IS 'Prix de référence au moment de l''approbation : base de referenceChangedSinceApproval (v16-D3).';

CREATE TABLE channel_price_head (
    link_id        uuid        NOT NULL,
    product_id     uuid        NOT NULL,
    price_version  bigint      NOT NULL DEFAULT 0,
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_channel_price_head PRIMARY KEY (link_id, product_id),
    CONSTRAINT ck_channel_price_head_version CHECK (price_version >= 0)
);

COMMENT ON TABLE channel_price_head IS 'Verrou des propositions de prix par (liaison, produit) (C6) et compteur de leur rang.';
COMMENT ON COLUMN channel_price_head.price_version IS 'Rang de la dernière proposition du couple ; jamais en recul.';

CREATE TABLE integration_idempotency (
    link_id          uuid         NOT NULL,
    operation        varchar(40)  NOT NULL,
    idempotency_key  varchar(100) NOT NULL,
    request_hash     char(64)     NOT NULL,
    response_status  smallint     NOT NULL,
    response_body    text         NOT NULL,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_integration_idempotency PRIMARY KEY (link_id, operation, idempotency_key),
    CONSTRAINT ck_integration_idempotency_key CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{8,100}$'),
    CONSTRAINT ck_integration_idempotency_hash CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

COMMENT ON TABLE integration_idempotency IS 'Réponses enregistrées des écritures des boutiques (Idempotency-Key) : une requête rejouée renvoie la même réponse ; jamais purgée (plan § 36).';
COMMENT ON COLUMN integration_idempotency.request_hash IS 'Empreinte SHA-256 de la requête normalisée : même clé, autre requête ⇒ 409 IDEMPOTENCY_KEY_REUSED.';
COMMENT ON COLUMN integration_idempotency.response_body IS 'Corps JSON de la réponse enregistrée, renvoyé tel quel.';
