-- =============================================================================
-- V008 — Sécurité : refresh tokens et révocation des sessions
--
-- refresh_token : jetons opaques de renouvellement. Seul leur haché SHA-256 est
-- stocké (une fuite de la base ne permet pas d'ouvrir de session). Rotation à chaque
-- usage : l'ancien jeton est révoqué et remplacé dans la même famille. La réutilisation
-- d'un jeton déjà révoqué trahit un vol : toute la famille est révoquée.
--
-- app_user.credentials_changed_at : tout jeton d'accès émis avant cette date est
-- refusé (changement ou réinitialisation de mot de passe).
--
-- replaced_by_id ne porte pas de clé étrangère : la purge des jetons expirés supprime
-- des familles entières sans contrainte d'ordre.
--
-- Annulation : db/rollback/U008__security_refresh_token.sql
-- =============================================================================

CREATE TABLE refresh_token (
    id             uuid        NOT NULL DEFAULT gen_random_uuid(),
    user_id        uuid        NOT NULL,
    token_hash     varchar(64) NOT NULL,
    family_id      uuid        NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    expires_at     timestamptz NOT NULL,
    revoked_at     timestamptz,
    revoked_reason varchar(30),
    replaced_by_id uuid,
    user_agent     varchar(255),
    ip_address     varchar(45),
    CONSTRAINT pk_refresh_token PRIMARY KEY (id),
    CONSTRAINT fk_refresh_token_user FOREIGN KEY (user_id)
        REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT uk_refresh_token_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_refresh_token_hash_format CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_refresh_token_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_refresh_token_revocation CHECK (
        (revoked_at IS NULL AND revoked_reason IS NULL)
        OR (revoked_at IS NOT NULL AND revoked_reason IN
            ('ROTATED', 'LOGOUT', 'REUSE_DETECTED', 'CREDENTIALS_CHANGED', 'ACCOUNT_DISABLED')))
);

CREATE INDEX ix_refresh_token_user_id ON refresh_token (user_id);
CREATE INDEX ix_refresh_token_family_id ON refresh_token (family_id);
CREATE INDEX ix_refresh_token_expires_at ON refresh_token (expires_at);

COMMENT ON TABLE refresh_token IS 'Jetons de renouvellement de session (haché SHA-256 uniquement), avec rotation.';
COMMENT ON COLUMN refresh_token.family_id IS 'Chaîne de rotation issue d''une même connexion.';

ALTER TABLE app_user ADD COLUMN credentials_changed_at timestamptz;

COMMENT ON COLUMN app_user.credentials_changed_at IS
    'Les jetons d''accès émis avant cette date sont refusés (changement de mot de passe).';
