-- =============================================================================
-- V006 — Journal d'audit métier
--
-- Table en ajout seul (trigger fn_prevent_modification). Pas de clé étrangère :
-- le journal doit survivre à la désactivation ou à la suppression de ce qu'il décrit.
-- Les libellés (username, entity_label) sont des instantanés au moment de l'action.
--
-- action et entity_type sont des catalogues ouverts : contrôle de format seulement,
-- pour ne pas imposer une migration à chaque nouvelle action tracée.
--
-- Annulation : db/rollback/U006__audit_log.sql
-- =============================================================================

CREATE TABLE audit_log (
    id           uuid         NOT NULL DEFAULT gen_random_uuid(),
    created_at   timestamptz  NOT NULL DEFAULT now(),
    user_id      uuid,
    username     varchar(50),
    user_role    varchar(50),
    action       varchar(30)  NOT NULL,
    entity_type  varchar(50)  NOT NULL,
    entity_id    varchar(100),
    entity_label varchar(255),
    status       varchar(10)  NOT NULL,
    details      text,
    changes      jsonb,
    before_state jsonb,
    after_state  jsonb,
    meta         jsonb,
    ip_address   varchar(45),
    user_agent   varchar(255),
    warehouse_id uuid,
    trace_id     varchar(64),
    CONSTRAINT pk_audit_log PRIMARY KEY (id),
    CONSTRAINT ck_audit_log_action_format CHECK (action ~ '^[A-Z][A-Z0-9_]*$'),
    CONSTRAINT ck_audit_log_entity_type_format CHECK (entity_type ~ '^[A-Z][A-Z0-9_]*$'),
    CONSTRAINT ck_audit_log_status CHECK (status IN ('SUCCESS', 'FAILURE'))
);

-- Consultation : par date (liste paginée), par utilisateur, par entité, par action.
CREATE INDEX ix_audit_log_created_at ON audit_log (created_at DESC);
CREATE INDEX ix_audit_log_user_id_created_at ON audit_log (user_id, created_at DESC);
CREATE INDEX ix_audit_log_entity_type_entity_id ON audit_log (entity_type, entity_id);
CREATE INDEX ix_audit_log_action_created_at ON audit_log (action, created_at DESC);

CREATE TRIGGER trg_audit_log_prevent_modification
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION fn_prevent_modification();

CREATE TRIGGER trg_audit_log_prevent_truncate
    BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION fn_prevent_modification();

COMMENT ON TABLE audit_log IS 'Journal d''audit métier, en ajout seul (UPDATE, DELETE et TRUNCATE refusés).';
COMMENT ON COLUMN audit_log.changes IS 'Différences champ par champ : [{"field", "oldValue", "newValue"}].';
COMMENT ON COLUMN audit_log.trace_id IS 'Identifiant de corrélation de la requête HTTP (en-tête X-Trace-Id).';
