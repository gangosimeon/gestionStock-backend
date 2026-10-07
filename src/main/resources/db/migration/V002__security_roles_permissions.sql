-- =============================================================================
-- V002 — Sécurité : rôles, permissions et matrice rôle → permissions
--
-- Modèle : utilisateur → rôles → permissions « domaine:action ».
-- Les rôles système (built_in) sont créés par V003 et ne peuvent pas être supprimés
-- par l'application.
--
-- Annulation : db/rollback/U002__security_roles_permissions.sql
-- =============================================================================

CREATE TABLE role (
    id          uuid         NOT NULL DEFAULT gen_random_uuid(),
    code        varchar(50)  NOT NULL,
    label       varchar(100) NOT NULL,
    description varchar(255),
    built_in    boolean      NOT NULL DEFAULT false,
    version     bigint       NOT NULL DEFAULT 0,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    created_by  uuid,
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    updated_by  uuid,
    CONSTRAINT pk_role PRIMARY KEY (id),
    CONSTRAINT uk_role_code UNIQUE (code),
    CONSTRAINT ck_role_code_format CHECK (code ~ '^[A-Z][A-Z0-9_]*$')
);

COMMENT ON TABLE role IS 'Rôles attribuables aux utilisateurs (ADMIN, GESTIONNAIRE, CAISSIER...).';
COMMENT ON COLUMN role.built_in IS 'Rôle système livré par les migrations : non supprimable.';

CREATE TABLE permission (
    id          uuid         NOT NULL DEFAULT gen_random_uuid(),
    code        varchar(100) NOT NULL,
    description varchar(255) NOT NULL,
    version     bigint       NOT NULL DEFAULT 0,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    created_by  uuid,
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    updated_by  uuid,
    CONSTRAINT pk_permission PRIMARY KEY (id),
    CONSTRAINT uk_permission_code UNIQUE (code),
    CONSTRAINT ck_permission_code_format CHECK (code ~ '^[a-z][a-z-]*:[a-z][a-z-]*$')
);

COMMENT ON TABLE permission IS 'Catalogue des permissions, au format domaine:action (ex. product:write).';

CREATE TABLE role_permission (
    role_id       uuid NOT NULL,
    permission_id uuid NOT NULL,
    CONSTRAINT pk_role_permission PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id)
        REFERENCES role (id) ON DELETE CASCADE,
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id)
        REFERENCES permission (id) ON DELETE RESTRICT
);

-- role_id est couvert par la clé primaire ; permission_id a besoin de son propre index.
CREATE INDEX ix_role_permission_permission_id ON role_permission (permission_id);

COMMENT ON TABLE role_permission IS 'Matrice rôle → permissions.';
