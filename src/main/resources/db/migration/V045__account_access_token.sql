-- =============================================================================
-- Liens d'accès à usage unique (activation du compte initial, réinitialisation du
-- mot de passe) et permission de gestion du profil de l'entreprise.
-- Seul le haché SHA-256 du jeton est stocké ; le lien en clair n'est montré qu'une fois.
-- =============================================================================

CREATE TABLE account_access_token (
    id          uuid         NOT NULL DEFAULT gen_random_uuid(),
    user_id     uuid         NOT NULL,
    purpose     varchar(20)  NOT NULL,
    token_hash  varchar(64)  NOT NULL,
    expires_at  timestamptz  NOT NULL,
    used_at     timestamptz,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    created_by  uuid,
    CONSTRAINT pk_account_access_token PRIMARY KEY (id),
    CONSTRAINT fk_account_access_token_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT ck_account_access_token_purpose CHECK (purpose IN ('ACTIVATION', 'PASSWORD_RESET')),
    CONSTRAINT ck_account_access_token_expiry CHECK (expires_at > created_at)
);

CREATE UNIQUE INDEX uk_account_access_token_hash ON account_access_token (token_hash);
CREATE INDEX ix_account_access_token_user_id ON account_access_token (user_id);

COMMENT ON TABLE account_access_token IS 'Liens d''accès à usage unique (activation, réinitialisation). Haché seulement.';

INSERT INTO permission (code, description) VALUES
    ('company:manage', 'Modifier le profil et l''identité visuelle de l''entreprise (si GestStock l''autorise)');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r CROSS JOIN permission p
WHERE r.code = 'ADMIN' AND p.code = 'company:manage';
