-- =============================================================================
-- Base plateforme GestStock (schéma « platform ») : entreprises, bases dédiées,
-- provisionnement, personnalisation, Super Admin et journal d'administration.
-- Aucune donnée métier d'entreprise ici : elles vivent dans la base de chaque entreprise.
-- =============================================================================

CREATE SEQUENCE platform.company_number_seq START WITH 1;

CREATE TABLE platform.company (
    id                     uuid         NOT NULL DEFAULT gen_random_uuid(),
    number                 integer      NOT NULL DEFAULT nextval('platform.company_number_seq'),
    code                   varchar(40)  NOT NULL,
    legal_name             varchar(150) NOT NULL,
    trade_name             varchar(150) NOT NULL,
    sector                 varchar(100),
    address                varchar(255),
    city                   varchar(100),
    country                varchar(100),
    phone                  varchar(30),
    email                  varchar(254),
    website                varchar(200),
    tax_id                 varchar(100),
    currency               varchar(10)  NOT NULL DEFAULT 'FCFA',
    owner_first_name       varchar(100),
    owner_last_name        varchar(100) NOT NULL,
    owner_email            varchar(254),
    owner_phone            varchar(30),
    admin_username         varchar(50)  NOT NULL,
    plan                   varchar(30)  NOT NULL DEFAULT 'STANDARD',
    subscription_starts_on date,
    subscription_ends_on   date,
    status                 varchar(20)  NOT NULL DEFAULT 'PENDING',
    status_reason          varchar(300),
    idempotency_key        varchar(100),
    version                bigint       NOT NULL DEFAULT 0,
    created_at             timestamptz  NOT NULL DEFAULT now(),
    created_by             uuid,
    updated_at             timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_company PRIMARY KEY (id),
    CONSTRAINT uk_company_number UNIQUE (number),
    CONSTRAINT ck_company_code_format CHECK (code ~ '^[a-z0-9]([a-z0-9-]{0,38}[a-z0-9])?$'),
    CONSTRAINT ck_company_status CHECK (status IN ('PENDING', 'PROVISIONING', 'ACTIVE', 'FAILED', 'SUSPENDED')),
    CONSTRAINT ck_company_admin_username CHECK (admin_username ~ '^[A-Za-z0-9._-]{3,50}$'),
    CONSTRAINT ck_company_subscription_dates CHECK (subscription_ends_on IS NULL OR subscription_starts_on IS NULL
        OR subscription_ends_on >= subscription_starts_on)
);

CREATE UNIQUE INDEX uk_company_code ON platform.company (code);
CREATE UNIQUE INDEX uk_company_idempotency_key ON platform.company (idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX ix_company_status ON platform.company (status);

COMMENT ON TABLE platform.company IS 'Entreprises clientes. code : identifiant public (connexion, marque), jamais un nom de base.';
COMMENT ON COLUMN platform.company.number IS 'Numéro interne : seule source des noms de base et de rôle PostgreSQL.';

CREATE TABLE platform.company_database (
    company_id         uuid         NOT NULL,
    mode               varchar(10)  NOT NULL,
    database_name      varchar(63)  NOT NULL,
    runtime_role       varchar(63),
    runtime_password   text,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_company_database PRIMARY KEY (company_id),
    CONSTRAINT fk_company_database_company FOREIGN KEY (company_id) REFERENCES platform.company (id) ON DELETE RESTRICT,
    CONSTRAINT ck_company_database_mode CHECK (mode IN ('LEGACY', 'DEDICATED')),
    CONSTRAINT ck_company_database_name CHECK (database_name ~ '^[a-z0-9_]{1,63}$'),
    CONSTRAINT ck_company_database_role CHECK (runtime_role IS NULL OR runtime_role ~ '^[a-z0-9_]{1,63}$'),
    CONSTRAINT ck_company_database_dedicated CHECK (mode = 'LEGACY' OR (runtime_role IS NOT NULL AND runtime_password IS NOT NULL))
);

CREATE UNIQUE INDEX uk_company_database_name ON platform.company_database (database_name);

COMMENT ON TABLE platform.company_database IS 'Base de chaque entreprise. LEGACY : base historique, identifiants applicatifs existants.';
COMMENT ON COLUMN platform.company_database.runtime_password IS 'Mot de passe du rôle d''exécution, chiffré AES-256-GCM (jamais en clair).';

CREATE TABLE platform.provisioning_job (
    id             uuid         NOT NULL DEFAULT gen_random_uuid(),
    company_id     uuid         NOT NULL,
    status         varchar(20)  NOT NULL DEFAULT 'PENDING',
    current_step   varchar(30),
    attempts       integer      NOT NULL DEFAULT 0,
    last_error     varchar(500),
    created_at     timestamptz  NOT NULL DEFAULT now(),
    started_at     timestamptz,
    finished_at    timestamptz,
    CONSTRAINT pk_provisioning_job PRIMARY KEY (id),
    CONSTRAINT fk_provisioning_job_company FOREIGN KEY (company_id) REFERENCES platform.company (id) ON DELETE RESTRICT,
    CONSTRAINT ck_provisioning_job_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED'))
);

CREATE UNIQUE INDEX uk_provisioning_job_company ON platform.provisioning_job (company_id);

CREATE TABLE platform.provisioning_step (
    id           uuid         NOT NULL DEFAULT gen_random_uuid(),
    job_id       uuid         NOT NULL,
    step         varchar(30)  NOT NULL,
    status       varchar(20)  NOT NULL,
    message      varchar(500),
    attempt      integer      NOT NULL,
    occurred_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_provisioning_step PRIMARY KEY (id),
    CONSTRAINT fk_provisioning_step_job FOREIGN KEY (job_id) REFERENCES platform.provisioning_job (id) ON DELETE CASCADE,
    CONSTRAINT ck_provisioning_step_status CHECK (status IN ('DONE', 'SKIPPED', 'FAILED'))
);

CREATE INDEX ix_provisioning_step_job ON platform.provisioning_step (job_id, occurred_at);

CREATE TABLE platform.company_branding (
    company_id              uuid         NOT NULL,
    primary_color           varchar(7)   NOT NULL DEFAULT '#2563EB',
    secondary_color         varchar(7)   NOT NULL DEFAULT '#7C3AED',
    accent_color            varchar(7)   NOT NULL DEFAULT '#16A34A',
    sidebar_color           varchar(7)   NOT NULL DEFAULT '#0B1F3A',
    header_color            varchar(7)   NOT NULL DEFAULT '#FFFFFF',
    button_color            varchar(7)   NOT NULL DEFAULT '#2563EB',
    link_color              varchar(7)   NOT NULL DEFAULT '#2563EB',
    theme_mode              varchar(10)  NOT NULL DEFAULT 'LIGHT',
    high_contrast           boolean      NOT NULL DEFAULT false,
    company_can_edit        boolean      NOT NULL DEFAULT false,
    footer_text             varchar(300),
    logo                    bytea,
    logo_content_type       varchar(30),
    logo_updated_at         timestamptz,
    version                 bigint       NOT NULL DEFAULT 0,
    updated_at              timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_company_branding PRIMARY KEY (company_id),
    CONSTRAINT fk_company_branding_company FOREIGN KEY (company_id) REFERENCES platform.company (id) ON DELETE CASCADE,
    CONSTRAINT ck_company_branding_colors CHECK (
        primary_color ~ '^#[0-9A-F]{6}$' AND secondary_color ~ '^#[0-9A-F]{6}$' AND accent_color ~ '^#[0-9A-F]{6}$'
        AND sidebar_color ~ '^#[0-9A-F]{6}$' AND header_color ~ '^#[0-9A-F]{6}$' AND button_color ~ '^#[0-9A-F]{6}$'
        AND link_color ~ '^#[0-9A-F]{6}$'),
    CONSTRAINT ck_company_branding_theme CHECK (theme_mode IN ('LIGHT', 'DARK')),
    CONSTRAINT ck_company_branding_logo_type CHECK (logo_content_type IS NULL
        OR logo_content_type IN ('image/png', 'image/jpeg', 'image/webp')),
    CONSTRAINT ck_company_branding_logo_size CHECK (logo IS NULL OR octet_length(logo) <= 1048576)
);

COMMENT ON TABLE platform.company_branding IS 'Identité visuelle : couleurs validées #RRGGBB et logo vérifié. Aucun CSS libre.';

CREATE TABLE platform.platform_admin (
    id                     uuid         NOT NULL DEFAULT gen_random_uuid(),
    username               varchar(50)  NOT NULL,
    full_name              varchar(150) NOT NULL,
    email                  varchar(254),
    password_hash          varchar(255),
    active                 boolean      NOT NULL DEFAULT true,
    must_change_password   boolean      NOT NULL DEFAULT true,
    failed_login_attempts  integer      NOT NULL DEFAULT 0,
    locked_until           timestamptz,
    last_login_at          timestamptz,
    credentials_changed_at timestamptz,
    created_at             timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_platform_admin PRIMARY KEY (id),
    CONSTRAINT ck_platform_admin_username CHECK (username ~ '^[A-Za-z0-9._-]{3,50}$')
);

CREATE UNIQUE INDEX uk_platform_admin_username ON platform.platform_admin (lower(username));

COMMENT ON TABLE platform.platform_admin IS 'Super Admin GestStock. Jamais créé par inscription publique (amorçage par variables d''environnement).';

CREATE TABLE platform.platform_refresh_token (
    id           uuid         NOT NULL DEFAULT gen_random_uuid(),
    admin_id     uuid         NOT NULL,
    token_hash   varchar(64)  NOT NULL,
    family_id    uuid         NOT NULL,
    issued_at    timestamptz  NOT NULL,
    expires_at   timestamptz  NOT NULL,
    revoked_at   timestamptz,
    CONSTRAINT pk_platform_refresh_token PRIMARY KEY (id),
    CONSTRAINT fk_platform_refresh_token_admin FOREIGN KEY (admin_id) REFERENCES platform.platform_admin (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uk_platform_refresh_token_hash ON platform.platform_refresh_token (token_hash);
CREATE INDEX ix_platform_refresh_token_family ON platform.platform_refresh_token (family_id);

CREATE TABLE platform.platform_audit_log (
    id           uuid         NOT NULL DEFAULT gen_random_uuid(),
    occurred_at  timestamptz  NOT NULL DEFAULT now(),
    actor_id     uuid,
    actor_name   varchar(50),
    action       varchar(50)  NOT NULL,
    company_id   uuid,
    details      varchar(1000),
    trace_id     varchar(64),
    CONSTRAINT pk_platform_audit_log PRIMARY KEY (id)
);

CREATE INDEX ix_platform_audit_log_occurred ON platform.platform_audit_log (occurred_at DESC);
CREATE INDEX ix_platform_audit_log_company ON platform.platform_audit_log (company_id, occurred_at DESC);

COMMENT ON TABLE platform.platform_audit_log IS 'Opérations d''administration de la plateforme. Jamais de secret dans details.';
