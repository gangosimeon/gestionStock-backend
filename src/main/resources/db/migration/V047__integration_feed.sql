-- =============================================================================
-- V047 — Flux d'événements d'intégration (boutique en ligne JeCréeMaBoutique)
--
-- Conception D (plan d'intégration § 20.2 et § 22) : chaque changement publié écrit un événement
-- SANS numéro de séquence ; le numéro (seq) est attribué plus tard, à la lecture du flux, sous un
-- verrou à lecteur unique, aux seuls événements de transactions validées. Ainsi une transaction
-- lente ne peut jamais faire apparaître un événement derrière un curseur déjà avancé.
--   - write_id : ordre d'écriture, seule clé d'ordre d'attribution, jamais exposé ;
--   - seq      : position publique dans le flux, nulle tant que l'événement n'est pas séquencé ;
--   - integration_feed_state : une seule ligne (dernier seq attribué, repère de purge).
--
-- Additive : aucune table existante n'est modifiée. Annulation : db/rollback/U047__integration_feed.sql
-- =============================================================================

CREATE TABLE integration_outbox (
    id             uuid         NOT NULL DEFAULT gen_random_uuid(),
    write_id       bigint       GENERATED ALWAYS AS IDENTITY,
    seq            bigint,
    type           varchar(40)  NOT NULL,
    operation      varchar(10)  NOT NULL,
    object_id      uuid         NOT NULL,
    object_version bigint       NOT NULL,
    payload        jsonb,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_integration_outbox PRIMARY KEY (id),
    CONSTRAINT uk_integration_outbox_write_id UNIQUE (write_id),
    -- Contrainte UNIQUE : plusieurs NULL permis (événements en attente), jamais deux fois le même seq.
    CONSTRAINT uk_integration_outbox_seq UNIQUE (seq),
    CONSTRAINT ck_integration_outbox_operation CHECK (operation IN ('UPSERT', 'ARCHIVE')),
    CONSTRAINT ck_integration_outbox_seq CHECK (seq IS NULL OR seq > 0),
    CONSTRAINT ck_integration_outbox_object_version CHECK (object_version >= 0)
);

-- Événements en attente de séquencement, dans l'ordre d'écriture (attribution du seq).
CREATE INDEX ix_integration_outbox_unsequenced ON integration_outbox (write_id) WHERE seq IS NULL;
-- Purge par ancienneté (rétention, § 5.6).
CREATE INDEX ix_integration_outbox_created_at ON integration_outbox (created_at);

COMMENT ON TABLE integration_outbox IS 'Événements d''intégration écrits dans la transaction métier ; seq attribué à la lecture du flux (conception D).';
COMMENT ON COLUMN integration_outbox.id IS 'Identifiant technique de l''événement (UUID v7 fourni par l''application).';
COMMENT ON COLUMN integration_outbox.write_id IS 'Ordre d''écriture, généré par la base ; seule clé d''ordre d''attribution du seq ; jamais exposé.';
COMMENT ON COLUMN integration_outbox.seq IS 'Position publique dans le flux ; NULL tant que l''événement n''est pas séquencé ; unique.';
COMMENT ON COLUMN integration_outbox.type IS 'Type d''événement du contrat (CATEGORY, PRODUCT, PRODUCT_IMAGE, AVAILABILITY…).';
COMMENT ON COLUMN integration_outbox.operation IS 'UPSERT ou ARCHIVE.';
COMMENT ON COLUMN integration_outbox.object_id IS 'Identifiant de l''objet décrit (pour PRODUCT_IMAGE et AVAILABILITY : le produit).';
COMMENT ON COLUMN integration_outbox.object_version IS 'Version de l''objet (champ « version » du contrat), strictement croissante par objet.';
COMMENT ON COLUMN integration_outbox.payload IS 'Charge minimale de l''événement (jamais de donnée personnelle) ; NULL pour un événement mince.';
COMMENT ON COLUMN integration_outbox.created_at IS 'Horodatage d''écriture (base de la rétention).';

CREATE TABLE integration_feed_state (
    id                 uuid         NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001',
    last_seq           bigint       NOT NULL DEFAULT 0,
    purged_through_seq bigint       NOT NULL DEFAULT 0,
    updated_at         timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_integration_feed_state PRIMARY KEY (id),
    -- Ligne unique : une seule valeur de clé est admise.
    CONSTRAINT ck_integration_feed_state_singleton CHECK (id = '00000000-0000-0000-0000-000000000001'),
    CONSTRAINT ck_integration_feed_state_seq CHECK (last_seq >= 0 AND purged_through_seq >= 0 AND purged_through_seq <= last_seq)
);

INSERT INTO integration_feed_state (id) VALUES ('00000000-0000-0000-0000-000000000001');

COMMENT ON TABLE integration_feed_state IS 'État du flux d''intégration : une seule ligne, verrouillée par le séquenceur (FOR UPDATE SKIP LOCKED).';
COMMENT ON COLUMN integration_feed_state.id IS 'Clé constante (ligne unique).';
COMMENT ON COLUMN integration_feed_state.last_seq IS 'Dernier seq attribué ; 0 tant qu''aucun événement n''est séquencé.';
COMMENT ON COLUMN integration_feed_state.purged_through_seq IS 'Repère de purge : plus haut seq purgé ; un curseur inférieur reçoit 410 CURSOR_EXPIRED.';
COMMENT ON COLUMN integration_feed_state.updated_at IS 'Dernière attribution ou purge.';
