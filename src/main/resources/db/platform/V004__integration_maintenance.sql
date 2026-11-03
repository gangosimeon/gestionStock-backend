-- =============================================================================
-- V004 — Maintenance de l'intégration boutique (lot 3, étape G20a ; plan § 33, G20-D4, D12, D13)
--
-- Bail unique de la maintenance (route interne appelée par le cron) : une seule ligne, génération croissante
-- (jeton de clôture), toutes les heures lues dans PostgreSQL. Un passage ne travaille que tant que sa
-- génération est celle du bail et que l'échéance n'est pas passée ; la sécurité des données de la purge ne
-- dépend pas du bail (règles de G17), il évite seulement le travail en double.
--
-- État de la maintenance par entreprise : tentative (début, génération), fin et résultat, interruptions
-- consécutives ; il fixe l'ordre de passage (équité entre entreprises, reprise après interruption).
--
-- Jamais de secret ni d'empreinte du secret du cron (connue du seul processus, par sa configuration).
-- Annulation : db/platform-rollback/U004__integration_maintenance.sql
-- =============================================================================

CREATE TABLE platform.integration_maintenance_lease (
    id            smallint     NOT NULL DEFAULT 1,
    generation    bigint       NOT NULL DEFAULT 0,
    owner         uuid,
    acquired_at   timestamptz,
    expires_at    timestamptz,
    released_at   timestamptz,
    CONSTRAINT pk_integration_maintenance_lease PRIMARY KEY (id),
    CONSTRAINT ck_integration_maintenance_lease_single CHECK (id = 1),
    CONSTRAINT ck_integration_maintenance_lease_generation CHECK (generation >= 0),
    CONSTRAINT ck_integration_maintenance_lease_taken CHECK ((generation = 0) = (owner IS NULL)
        AND (owner IS NULL) = (acquired_at IS NULL) AND (owner IS NULL) = (expires_at IS NULL)),
    CONSTRAINT ck_integration_maintenance_lease_expiry CHECK (expires_at > acquired_at),
    CONSTRAINT ck_integration_maintenance_lease_release CHECK (released_at IS NULL
        OR (acquired_at IS NOT NULL AND released_at >= acquired_at))
);

INSERT INTO platform.integration_maintenance_lease (id) VALUES (1);

COMMENT ON TABLE platform.integration_maintenance_lease IS 'Bail unique de la maintenance de l''intégration (G20-D4, D12) : une ligne, génération croissante ; heures de PostgreSQL.';
COMMENT ON COLUMN platform.integration_maintenance_lease.id IS 'Toujours 1 (ligne unique).';
COMMENT ON COLUMN platform.integration_maintenance_lease.generation IS 'Numéro du dernier passage qui a pris le bail (0 : jamais pris) ; jeton de clôture vérifié avant chaque entreprise et chaque lot.';
COMMENT ON COLUMN platform.integration_maintenance_lease.owner IS 'Identifiant aléatoire du passage propriétaire, tiré à chaque prise.';
COMMENT ON COLUMN platform.integration_maintenance_lease.acquired_at IS 'Prise du bail.';
COMMENT ON COLUMN platform.integration_maintenance_lease.expires_at IS 'Échéance : deux fois le budget du passage ; un bail échu peut être repris.';
COMMENT ON COLUMN platform.integration_maintenance_lease.released_at IS 'Libération par le passage propriétaire (nul : bail tenu ou abandonné).';

CREATE TABLE platform.integration_maintenance_company (
    company_id                 uuid         NOT NULL,
    last_started_at            timestamptz  NOT NULL,
    last_started_generation    bigint       NOT NULL,
    last_completed_at          timestamptz,
    last_outcome               varchar(20),
    consecutive_interruptions  integer      NOT NULL DEFAULT 0,
    CONSTRAINT pk_integration_maintenance_company PRIMARY KEY (company_id),
    CONSTRAINT fk_integration_maintenance_company_company FOREIGN KEY (company_id)
        REFERENCES platform.company (id) ON DELETE CASCADE,
    CONSTRAINT ck_integration_maintenance_company_outcome CHECK (last_outcome IN ('OK', 'PARTIAL', 'SKIPPED_UPGRADE', 'ERROR')),
    CONSTRAINT ck_integration_maintenance_company_completion CHECK ((last_completed_at IS NULL) = (last_outcome IS NULL)),
    CONSTRAINT ck_integration_maintenance_company_generation CHECK (last_started_generation > 0),
    CONSTRAINT ck_integration_maintenance_company_interruptions CHECK (consecutive_interruptions >= 0)
);

COMMENT ON TABLE platform.integration_maintenance_company IS 'État de la maintenance de l''intégration par entreprise (G20-D13) : ordre de passage et reprise après interruption.';
COMMENT ON COLUMN platform.integration_maintenance_company.company_id IS 'Entreprise (platform.company.id) ; état supprimé avec elle.';
COMMENT ON COLUMN platform.integration_maintenance_company.last_started_at IS 'Début de la dernière tentative (écrit au début du traitement de l''entreprise).';
COMMENT ON COLUMN platform.integration_maintenance_company.last_started_generation IS 'Génération du bail du passage qui a commencé la dernière tentative : lui seul peut la terminer.';
COMMENT ON COLUMN platform.integration_maintenance_company.last_completed_at IS 'Fin de la dernière tentative terminée, quel que soit son résultat ; antérieure au début : tentative interrompue.';
COMMENT ON COLUMN platform.integration_maintenance_company.last_outcome IS 'Résultat de la dernière tentative terminée : OK, PARTIAL (budget épuisé), SKIPPED_UPGRADE (migration), ERROR.';
COMMENT ON COLUMN platform.integration_maintenance_company.consecutive_interruptions IS 'Interruptions consécutives constatées au début des tentatives suivantes ; remis à 0 par chaque fin.';
