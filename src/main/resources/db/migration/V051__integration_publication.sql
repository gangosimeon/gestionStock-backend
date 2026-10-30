-- =============================================================================
-- V051 — Drapeau de publication du flux d'intégration (lot 2, étape G13 ; v12-D4, v14-D11)
--
-- L'entreprise n'écrit d'événements que si elle a au moins une liaison active ou suspendue (la publication
-- continue pendant une suspension). Une seule ligne, distincte de integration_feed_state pour ne pas gêner le
-- FOR UPDATE SKIP LOCKED du séquenceur.
--
-- Fermeture de la course « drapeau lu à faux, liaison activée, instantané lu, transaction validée sans
-- événement » : chaque transaction qui publie lit le drapeau une fois avec un verrou partagé (FOR SHARE) ; le
-- passage à vrai prend un verrou exclusif, donc attend la fin des transactions qui l'ont lu à faux, et il a
-- lieu avant l'activation de la liaison (base plateforme). Les verrous partagés ne se bloquent pas entre eux.
--
-- Valeur initiale : faux. Additive. Annulation : db/rollback/U051__integration_publication.sql
-- =============================================================================

CREATE TABLE integration_publication (
    id          uuid         NOT NULL,
    enabled     boolean      NOT NULL DEFAULT false,
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_integration_publication PRIMARY KEY (id),
    CONSTRAINT ck_integration_publication_single_row CHECK (id = '00000000-0000-0000-0000-000000000001'::uuid)
);

INSERT INTO integration_publication (id, enabled) VALUES ('00000000-0000-0000-0000-000000000001', false);

COMMENT ON TABLE integration_publication IS 'Drapeau de publication du flux (v14-D11) : une ligne ; vrai si l''entreprise a au moins une liaison active ou suspendue.';
COMMENT ON COLUMN integration_publication.id IS 'Identifiant fixe de la ligne unique.';
COMMENT ON COLUMN integration_publication.enabled IS 'Vrai : les changements publient leurs événements (avec geststock.integration.publish.enabled).';
COMMENT ON COLUMN integration_publication.updated_at IS 'Dernier changement du drapeau.';
