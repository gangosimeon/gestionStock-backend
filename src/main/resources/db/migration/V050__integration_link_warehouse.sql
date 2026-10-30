-- =============================================================================
-- V050 — Dépôts vendables par liaison (intégration JeCréeMaBoutique, lot 2, étape G4a ; v11-D8, v14-D10)
--
-- Dépôts dont le stock compose la disponibilité servie à une liaison (flux et instantané), choisis par la
-- personne qui approuve la liaison et figés jusqu'à la réactivation suivante (reconfirmation). La liaison vit
-- dans la base plateforme (platform.integration_link) : son identifiant est conservé ici sans clé étrangère
-- possible. Une liaison sans ligne ici n'expose aucune disponibilité ; un dépôt désactivé est exclu au moment
-- de servir (jointure sur warehouse.active), un dépôt supprimé disparaît du périmètre (ON DELETE CASCADE).
--
-- Additive : aucune table existante n'est modifiée. Annulation : db/rollback/U050__integration_link_warehouse.sql
-- =============================================================================

CREATE TABLE integration_link_warehouse (
    link_id       uuid         NOT NULL,
    warehouse_id  uuid         NOT NULL,
    added_at      timestamptz  NOT NULL DEFAULT now(),
    added_by      uuid         NOT NULL,
    CONSTRAINT pk_integration_link_warehouse PRIMARY KEY (link_id, warehouse_id),
    CONSTRAINT fk_integration_link_warehouse_warehouse FOREIGN KEY (warehouse_id)
        REFERENCES warehouse (id) ON DELETE CASCADE
);

-- Clé étrangère couverte par un index de tête (le périmètre d'une liaison est lu par la clé primaire).
CREATE INDEX ix_integration_link_warehouse_warehouse ON integration_link_warehouse (warehouse_id);

COMMENT ON TABLE integration_link_warehouse IS 'Dépôts vendables de chaque liaison boutique (v11-D8) : figés à l''approbation, remplacés à la réactivation.';
COMMENT ON COLUMN integration_link_warehouse.link_id IS 'Liaison (platform.integration_link.id, autre base : sans clé étrangère).';
COMMENT ON COLUMN integration_link_warehouse.warehouse_id IS 'Dépôt dont le stock est vendable par la liaison (STORE ou WAREHOUSE).';
COMMENT ON COLUMN integration_link_warehouse.added_at IS 'Approbation ou réactivation qui a retenu ce dépôt.';
COMMENT ON COLUMN integration_link_warehouse.added_by IS 'Personne (app_user.id) qui a approuvé ou réactivé la liaison.';
