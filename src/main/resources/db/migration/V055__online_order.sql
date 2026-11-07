-- =============================================================================
-- V055 — Commandes en ligne des boutiques liées (intégration JeCréeMaBoutique, lot 3, étape G28)
--
-- online_order : commande reçue d'une liaison (POST /online-orders). Idempotence par (link_id, external_order_id) ;
-- write_id pour la détection des requêtes concurrent.
-- online_order_line : lignes de la commande (produit, quantité, prix approuvé).
-- online_reservation : réservation de stock créée pour une commande RESERVED.
-- online_reservation_line : lignes de la réservation, par (entrepôt, produit).
--
-- Aucune clé étrangère vers la base plateforme (link_id, autre base).
-- Additive. Annulation : db/rollback/U055__online_order.sql (tests seulement).
-- =============================================================================

CREATE TABLE online_order (
    id                 uuid          NOT NULL DEFAULT gen_random_uuid(),
    write_id           uuid          NOT NULL DEFAULT gen_random_uuid(),
    link_id            uuid          NOT NULL,
    external_order_id  varchar(64)   NOT NULL,
    request_hash       char(64)      NOT NULL,
    status             varchar(20)   NOT NULL DEFAULT 'RECEIVED',
    currency           char(3)       NOT NULL,
    customer_name      varchar(200)  NOT NULL,
    customer_phone     varchar(30),
    delivery_mode      varchar(10)   NOT NULL,
    delivery_address   varchar(500),
    note               varchar(500),
    cancel_reason      varchar(500),
    external_created_at timestamptz  NOT NULL,
    created_at         timestamptz   NOT NULL DEFAULT now(),
    updated_at         timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_online_order PRIMARY KEY (id),
    CONSTRAINT uk_online_order_external UNIQUE (link_id, external_order_id),
    CONSTRAINT ck_online_order_status CHECK (status IN (
        'RECEIVED', 'CHECKED', 'REJECTED', 'RESERVED', 'REJECTED_STOCK',
        'ACCEPTED', 'REFUSED', 'IN_PREPARATION', 'CONVERTED', 'CANCELLED', 'EXPIRED'
    )),
    CONSTRAINT ck_online_order_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_online_order_delivery CHECK (delivery_mode IN ('pickup', 'delivery')),
    CONSTRAINT ck_online_order_delivery_address CHECK (
        (delivery_mode = 'delivery') = (delivery_address IS NOT NULL)
    ),
    CONSTRAINT ck_online_order_hash CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_online_order_link_id ON online_order (link_id, created_at DESC, id);

COMMENT ON TABLE online_order IS 'Commandes en ligne reçues des boutiques liées (G28). Idempotence par (link_id, external_order_id).';
COMMENT ON COLUMN online_order.link_id IS 'Liaison (platform.integration_link.id, autre base : sans clé étrangère).';
COMMENT ON COLUMN online_order.write_id IS 'Identifiant d''idempotence de la création (détection de concurrence).';
COMMENT ON COLUMN online_order.external_order_id IS 'Identifiant métier de la commande côté boutique, unique par liaison.';
COMMENT ON COLUMN online_order.request_hash IS 'Empreinte SHA-256 du corps normalisé de la requête de création.';
COMMENT ON COLUMN online_order.status IS 'RECEIVED, CHECKED, REJECTED, RESERVED, REJECTED_STOCK, ACCEPTED, REFUSED, IN_PREPARATION, CONVERTED, CANCELLED, EXPIRED.';
COMMENT ON COLUMN online_order.delivery_mode IS 'pickup ou delivery ; adresse obligatoire si delivery.';

CREATE TABLE online_order_line (
    id              uuid          NOT NULL DEFAULT gen_random_uuid(),
    order_id        uuid          NOT NULL,
    product_id      uuid          NOT NULL,
    quantity        int           NOT NULL,
    unit_price      numeric(14,2) NOT NULL,
    price_version   bigint        NOT NULL,
    sku             varchar(100),
    label           varchar(200),
    CONSTRAINT pk_online_order_line PRIMARY KEY (id),
    CONSTRAINT fk_online_order_line_order FOREIGN KEY (order_id) REFERENCES online_order (id) ON DELETE RESTRICT,
    CONSTRAINT fk_online_order_line_product FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE RESTRICT,
    CONSTRAINT ck_online_order_line_quantity CHECK (quantity >= 1 AND quantity <= 9999),
    CONSTRAINT ck_online_order_line_price CHECK (unit_price > 0)
);

CREATE INDEX ix_online_order_line_order ON online_order_line (order_id);

COMMENT ON TABLE online_order_line IS 'Lignes d''une commande en ligne : produit, quantité et prix approuvé.';

CREATE TABLE online_reservation (
    id         uuid        NOT NULL DEFAULT gen_random_uuid(),
    order_id   uuid        NOT NULL,
    status     varchar(10) NOT NULL DEFAULT 'RESERVED',
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_online_reservation PRIMARY KEY (id),
    CONSTRAINT fk_online_reservation_order FOREIGN KEY (order_id) REFERENCES online_order (id) ON DELETE RESTRICT,
    CONSTRAINT uk_online_reservation_order UNIQUE (order_id),
    CONSTRAINT ck_online_reservation_status CHECK (status IN ('RESERVED', 'CONSUMED', 'CANCELLED', 'RELEASED', 'EXPIRED'))
);

COMMENT ON TABLE online_reservation IS 'Réservation de stock pour une commande RESERVED (G28).';
COMMENT ON COLUMN online_reservation.expires_at IS 'Date d''expiration de la réservation (now() + TTL configurable).';

CREATE TABLE online_reservation_line (
    id              uuid NOT NULL DEFAULT gen_random_uuid(),
    reservation_id  uuid NOT NULL,
    warehouse_id    uuid NOT NULL,
    product_id      uuid NOT NULL,
    quantity        int  NOT NULL,
    CONSTRAINT pk_online_reservation_line PRIMARY KEY (id),
    CONSTRAINT fk_online_reservation_line_reservation FOREIGN KEY (reservation_id) REFERENCES online_reservation (id) ON DELETE RESTRICT,
    CONSTRAINT fk_online_reservation_line_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse (id) ON DELETE RESTRICT,
    CONSTRAINT fk_online_reservation_line_product FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE RESTRICT,
    CONSTRAINT ck_online_reservation_line_quantity CHECK (quantity >= 1)
);

CREATE INDEX ix_online_reservation_line_res ON online_reservation_line (reservation_id);
CREATE INDEX ix_online_reservation_line_wh_prod ON online_reservation_line (warehouse_id, product_id);

COMMENT ON TABLE online_reservation_line IS 'Lignes d''une réservation de stock : quantité allouée par dépôt et produit.';
