-- =============================================================================
-- V003 — Audit de référence des liaisons boutique (lot 2, étape G12)
--
-- Une ligne par événement d'une liaison (approbation, refus, activation, suspension, réactivation,
-- révocation) ou tentative suspecte (code rejoué, échange refusé, client non authentifié), écrite dans la
-- même transaction que le changement de la liaison : une liaison ne change jamais d'état sans sa trace.
-- Les événements qui concernent une entreprise sont copiés dans son propre journal (audit_log de sa base),
-- après le commit, au mieux.
--
-- Jamais de secret, d'empreinte, de code, de request_uri, de jeton ni d'en-tête Authorization.
-- Pas de clé étrangère : la trace survit à la suppression d'une entreprise, d'un client ou d'une liaison.
-- Annulation : db/platform-rollback/U003__integration_audit.sql
-- =============================================================================

CREATE TABLE platform.integration_audit_log (
    id                 uuid          NOT NULL DEFAULT gen_random_uuid(),
    occurred_at        timestamptz   NOT NULL DEFAULT clock_timestamp(),
    action             varchar(40)   NOT NULL,
    severity           varchar(10)   NOT NULL DEFAULT 'INFO',
    company_id         uuid,
    link_id            uuid,
    client_id          uuid,
    announced_client   varchar(64),
    external_shop_id   varchar(64),
    actor_type         varchar(10)   NOT NULL,
    actor_id           uuid,
    actor_name         varchar(100),
    ip_address         varchar(45),
    reason             varchar(500),
    details            jsonb,
    trace_id           varchar(64),
    CONSTRAINT pk_integration_audit_log PRIMARY KEY (id),
    CONSTRAINT ck_integration_audit_log_action CHECK (action ~ '^[A-Z][A-Z0-9_]*$'),
    CONSTRAINT ck_integration_audit_log_severity CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    CONSTRAINT ck_integration_audit_log_actor_type CHECK (actor_type IN ('USER', 'SHOP', 'SYSTEM', 'CLIENT'))
);

CREATE INDEX ix_integration_audit_log_company ON platform.integration_audit_log (company_id, occurred_at DESC);
CREATE INDEX ix_integration_audit_log_link ON platform.integration_audit_log (link_id, occurred_at DESC);

COMMENT ON TABLE platform.integration_audit_log IS 'Audit de référence des liaisons boutique (G12) : écrit dans la transaction du changement ; jamais de secret, empreinte, code, request_uri ni jeton.';
COMMENT ON COLUMN platform.integration_audit_log.id IS 'Identifiant de la trace.';
COMMENT ON COLUMN platform.integration_audit_log.occurred_at IS 'Horodatage (horloge de PostgreSQL au moment de l''écriture : ordre des traces d''une même transaction).';
COMMENT ON COLUMN platform.integration_audit_log.action IS 'LINK_REQUEST_APPROVED, LINK_REQUEST_DENIED, LINK_ACTIVATED, LINK_SUSPENDED, LINK_REACTIVATED, LINK_REVOKED, CODE_REPLAY_DETECTED, CODE_EXCHANGE_REJECTED, CLIENT_AUTHENTICATION_FAILED.';
COMMENT ON COLUMN platform.integration_audit_log.severity IS 'INFO, WARNING (tentative refusée), CRITICAL (code rejoué).';
COMMENT ON COLUMN platform.integration_audit_log.company_id IS 'Entreprise concernée (platform.company.id), si connue.';
COMMENT ON COLUMN platform.integration_audit_log.link_id IS 'Liaison concernée (platform.integration_link.id), si connue.';
COMMENT ON COLUMN platform.integration_audit_log.client_id IS 'Client JeCréeMaBoutique authentifié ou propriétaire de la liaison (platform.integration_client.id).';
COMMENT ON COLUMN platform.integration_audit_log.announced_client IS 'Identifiant de client annoncé et refusé (invalid_client), normalisé ; jamais le secret.';
COMMENT ON COLUMN platform.integration_audit_log.external_shop_id IS 'Boutique (User._id JeCréeMaBoutique).';
COMMENT ON COLUMN platform.integration_audit_log.actor_type IS 'USER (personne GestStock), SHOP (boutique, via sa liaison), SYSTEM (réévaluation, expiration), CLIENT (client JeCréeMaBoutique).';
COMMENT ON COLUMN platform.integration_audit_log.actor_id IS 'USER : app_user.id dans la base de l''entreprise ; SHOP : identifiant de la liaison ; CLIENT : integration_client.id ; SYSTEM : nul.';
COMMENT ON COLUMN platform.integration_audit_log.actor_name IS 'USER : nom d''utilisateur ; SHOP : boutique ; CLIENT : client_id public ; SYSTEM : mécanisme (réévaluation des droits, expiration).';
COMMENT ON COLUMN platform.integration_audit_log.ip_address IS 'Adresse IP (en-tête configuré, v14-D8) des tentatives du client.';
COMMENT ON COLUMN platform.integration_audit_log.reason IS 'Motif libre de la boutique (POST /link/revoke), tel quel, 500 caractères au plus.';
COMMENT ON COLUMN platform.integration_audit_log.details IS 'Détails structurés : motif codé, portées, dépôts, personne responsable, nature d''un refus.';
COMMENT ON COLUMN platform.integration_audit_log.trace_id IS 'Identifiant de trace de la requête (X-Trace-Id).';
