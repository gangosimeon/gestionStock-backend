-- =============================================================================
-- V052 — Acquittements du flux d'intégration par liaison (lot 3, étape G17 ; D5, v17-D3, plan § 30.6)
--
-- Une ligne par liaison : le plus grand seq que la boutique a acquitté (POST /catalog/changes/ack). Dans la base de
-- l'entreprise, avec le flux et la purge : acquittements et événements restent cohérents après une restauration.
-- Aucune clé étrangère vers la base plateforme (autre base), comme integration_link_warehouse.link_id. Une liaison
-- active ou suspendue sans ligne bloque la purge (v17-D4) ; les lignes des liaisons révoquées sont ignorées.
--
-- Additive. Annulation : db/rollback/U052__integration_link_ack.sql (destructive, sans danger : la purge reste
-- bloquée jusqu'aux nouveaux acquittements, v17-D15).
-- =============================================================================

CREATE TABLE integration_link_ack (
    link_id           uuid         NOT NULL,
    acknowledged_seq  bigint       NOT NULL,
    acknowledged_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_integration_link_ack PRIMARY KEY (link_id),
    CONSTRAINT ck_integration_link_ack_seq CHECK (acknowledged_seq >= 0)
);

COMMENT ON TABLE integration_link_ack IS 'Acquittement du flux d''intégration par liaison (v17-D3) : plus grand seq acquitté, jamais en recul ; condition de purge (D5).';
COMMENT ON COLUMN integration_link_ack.link_id IS 'Liaison (platform.integration_link.id, autre base : sans clé étrangère).';
COMMENT ON COLUMN integration_link_ack.acknowledged_seq IS 'Plus grand seq acquitté par la liaison ; au plus le last_seq du flux au moment de l''acquittement.';
COMMENT ON COLUMN integration_link_ack.acknowledged_at IS 'Dernier acquittement accepté, même sans progression (âge de l''acquittement, métriques G20).';
