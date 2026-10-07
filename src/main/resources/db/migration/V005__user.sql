-- =============================================================================
-- V005 — Utilisateurs, affectation des rôles et dépôts autorisés
--
-- password_hash est NULL tant que l'utilisateur n'a pas reçu de mot de passe
-- (le front crée des utilisateurs sans mot de passe) : un tel compte ne peut pas
-- se connecter. Le format attendu est celui de DelegatingPasswordEncoder
-- ({bcrypt}..., {argon2}...).
--
-- Aucun administrateur n'est créé ici : un mot de passe, même haché, ne doit pas
-- être versionné. Le module sécurité le créera au premier démarrage à partir des
-- variables d'environnement.
--
-- Annulation : db/rollback/U005__user.sql
-- =============================================================================

CREATE TABLE app_user (
    id                    uuid         NOT NULL DEFAULT gen_random_uuid(),
    username              varchar(50)  NOT NULL,
    full_name             varchar(150) NOT NULL,
    email                 varchar(254),
    phone                 varchar(30),
    password_hash         varchar(255),
    must_change_password  boolean      NOT NULL DEFAULT true,
    active                boolean      NOT NULL DEFAULT true,
    failed_login_attempts integer      NOT NULL DEFAULT 0,
    locked_until          timestamptz,
    last_login_at         timestamptz,
    default_warehouse_id  uuid,
    version               bigint       NOT NULL DEFAULT 0,
    created_at            timestamptz  NOT NULL DEFAULT now(),
    created_by            uuid,
    updated_at            timestamptz  NOT NULL DEFAULT now(),
    updated_by            uuid,
    CONSTRAINT pk_app_user PRIMARY KEY (id),
    CONSTRAINT fk_app_user_default_warehouse FOREIGN KEY (default_warehouse_id)
        REFERENCES warehouse (id) ON DELETE RESTRICT,
    CONSTRAINT ck_app_user_username_format CHECK (username ~ '^[A-Za-z0-9._-]{3,50}$'),
    CONSTRAINT ck_app_user_full_name_not_blank CHECK (btrim(full_name) <> ''),
    CONSTRAINT ck_app_user_failed_attempts CHECK (failed_login_attempts >= 0)
);

-- Identifiants uniques, insensibles à la casse (règle RG-USR-1).
CREATE UNIQUE INDEX uk_app_user_username ON app_user (lower(username));
CREATE UNIQUE INDEX uk_app_user_email ON app_user (lower(email)) WHERE email IS NOT NULL;
CREATE INDEX ix_app_user_default_warehouse_id ON app_user (default_warehouse_id);

COMMENT ON TABLE app_user IS 'Comptes utilisateurs ("user" est un mot réservé PostgreSQL).';
COMMENT ON COLUMN app_user.password_hash IS 'Haché DelegatingPasswordEncoder ; NULL = connexion impossible.';
COMMENT ON COLUMN app_user.locked_until IS 'Verrouillage temporaire après trop d''échecs de connexion.';
COMMENT ON COLUMN app_user.default_warehouse_id IS 'Dépôt utilisé quand X-Warehouse-Id est absent ou inconnu.';

CREATE TABLE user_role (
    user_id uuid NOT NULL,
    role_id uuid NOT NULL,
    CONSTRAINT pk_user_role PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id)
        REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_role_role FOREIGN KEY (role_id)
        REFERENCES role (id) ON DELETE RESTRICT
);

CREATE INDEX ix_user_role_role_id ON user_role (role_id);

COMMENT ON TABLE user_role IS 'Rôles attribués à chaque utilisateur.';

CREATE TABLE user_warehouse (
    user_id      uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    CONSTRAINT pk_user_warehouse PRIMARY KEY (user_id, warehouse_id),
    CONSTRAINT fk_user_warehouse_user FOREIGN KEY (user_id)
        REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_warehouse_warehouse FOREIGN KEY (warehouse_id)
        REFERENCES warehouse (id) ON DELETE RESTRICT
);

CREATE INDEX ix_user_warehouse_warehouse_id ON user_warehouse (warehouse_id);

COMMENT ON TABLE user_warehouse IS 'Dépôts accessibles à chaque utilisateur (hors permission warehouse:all).';
