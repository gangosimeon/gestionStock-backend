-- =============================================================================
-- V002 (plateforme) — Registre des liaisons avec les boutiques en ligne (intégration JeCréeMaBoutique,
-- lot 2, étape G2 ; plan § 27 : v14-D2, D3, D5, D6, D9, D10, D11, D14 et ajustements v14).
--
-- Clients (un par environnement JeCréeMaBoutique) et leurs URL de retour, demandes d'autorisation déposées
-- de serveur à serveur (PAR), liaisons, codes d'autorisation, compteurs de débit.
-- Aucun secret, code ni request_uri en clair : seulement leur empreinte SHA-256 (64 caractères hexadécimaux).
-- Les dépôts vendables d'une liaison vivent dans la base de l'entreprise (étape G4), pas ici.
-- Retour arrière : db/platform-rollback/U002__integration_link.sql (v14-D9).
-- =============================================================================

CREATE TABLE platform.integration_client (
    id              uuid          NOT NULL DEFAULT gen_random_uuid(),
    client_id       varchar(64)   NOT NULL,
    name            varchar(100)  NOT NULL,
    secret_hash     varchar(64)   NOT NULL,
    secondary_secret_hash varchar(64),
    allowed_scopes  varchar(30)[] NOT NULL,
    status          varchar(20)   NOT NULL DEFAULT 'ACTIVE',
    created_at      timestamptz   NOT NULL DEFAULT now(),
    created_by      uuid,
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    disabled_at     timestamptz,
    CONSTRAINT pk_integration_client PRIMARY KEY (id),
    CONSTRAINT ck_integration_client_client_id CHECK (client_id ~ '^[a-z0-9][a-z0-9-]{2,62}[a-z0-9]$'),
    CONSTRAINT ck_integration_client_secret_hash CHECK (secret_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_integration_client_secondary_secret_hash CHECK (secondary_secret_hash IS NULL
        OR secondary_secret_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_integration_client_secondary_secret_distinct CHECK (secondary_secret_hash IS DISTINCT FROM secret_hash),
    CONSTRAINT ck_integration_client_scopes CHECK (cardinality(allowed_scopes) >= 1
        AND allowed_scopes <@ ARRAY['catalog:read', 'catalog:match', 'price:propose', 'stock:read', 'orders:read',
            'orders:write']::varchar(30)[]),
    CONSTRAINT ck_integration_client_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_integration_client_disabled CHECK ((status = 'DISABLED') = (disabled_at IS NOT NULL))
);

CREATE UNIQUE INDEX uk_integration_client_client_id ON platform.integration_client (client_id);

COMMENT ON TABLE platform.integration_client IS 'Client JeCréeMaBoutique, un par environnement (v14-D3), authentifié en HTTP Basic sur /oauth/par et /oauth/token.';
COMMENT ON COLUMN platform.integration_client.id IS 'Identifiant interne.';
COMMENT ON COLUMN platform.integration_client.client_id IS 'Identifiant public du client (HTTP Basic), unique.';
COMMENT ON COLUMN platform.integration_client.name IS 'Nom affiché sur l''écran de consentement.';
COMMENT ON COLUMN platform.integration_client.secret_hash IS 'Empreinte SHA-256 (hexadécimal) du secret client ; le secret n''est jamais stocké.';
COMMENT ON COLUMN platform.integration_client.secondary_secret_hash IS 'Empreinte SHA-256 d''un second secret client, acceptée en plus de secret_hash pendant une rotation du secret client ; nulle hors rotation, jamais égale à secret_hash.';
COMMENT ON COLUMN platform.integration_client.allowed_scopes IS 'Portées que ce client peut demander (valeurs du contrat).';
COMMENT ON COLUMN platform.integration_client.status IS 'ACTIVE ou DISABLED ; un client désactivé ne peut plus déposer de demande ni obtenir de jeton.';
COMMENT ON COLUMN platform.integration_client.created_at IS 'Date de création.';
COMMENT ON COLUMN platform.integration_client.created_by IS 'Super Admin qui a créé le client, nul si créé par amorçage.';
COMMENT ON COLUMN platform.integration_client.updated_at IS 'Date de dernière modification.';
COMMENT ON COLUMN platform.integration_client.disabled_at IS 'Date de désactivation, renseignée si et seulement si status = DISABLED.';

CREATE TABLE platform.integration_client_redirect_uri (
    id            uuid           NOT NULL DEFAULT gen_random_uuid(),
    client_id     uuid           NOT NULL,
    redirect_uri  varchar(2000)  NOT NULL,
    created_at    timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT pk_integration_client_redirect_uri PRIMARY KEY (id),
    CONSTRAINT fk_integration_client_redirect_uri_client FOREIGN KEY (client_id)
        REFERENCES platform.integration_client (id) ON DELETE CASCADE,
    CONSTRAINT ck_integration_client_redirect_uri_format CHECK (
        redirect_uri ~ '^https://[^\s#]+$' OR redirect_uri ~ '^http://localhost(:[0-9]{1,5})?(/[^\s#]*)?$')
);

CREATE UNIQUE INDEX uk_integration_client_redirect_uri ON platform.integration_client_redirect_uri (client_id, redirect_uri);

COMMENT ON TABLE platform.integration_client_redirect_uri IS 'Liste blanche des redirect_uri d''un client, comparées à l''identique (v14-D12). localhost réservé au développement et à l''essai (contrôle applicatif).';
COMMENT ON COLUMN platform.integration_client_redirect_uri.id IS 'Identifiant interne.';
COMMENT ON COLUMN platform.integration_client_redirect_uri.client_id IS 'Client propriétaire.';
COMMENT ON COLUMN platform.integration_client_redirect_uri.redirect_uri IS 'URL de retour exacte : https, ou http://localhost ; jamais de fragment.';
COMMENT ON COLUMN platform.integration_client_redirect_uri.created_at IS 'Date d''ajout.';

CREATE TABLE platform.integration_authorization_request (
    id                     uuid           NOT NULL DEFAULT gen_random_uuid(),
    request_uri_hash       varchar(64)    NOT NULL,
    client_id              uuid           NOT NULL,
    external_shop_id       varchar(64)    NOT NULL,
    shop_name              varchar(200)   NOT NULL,
    redirect_uri           varchar(2000)  NOT NULL,
    code_challenge         varchar(128)   NOT NULL,
    code_challenge_method  varchar(10)    NOT NULL DEFAULT 'S256',
    scopes                 varchar(30)[]  NOT NULL,
    state                  varchar(512)   NOT NULL,
    expires_at             timestamptz    NOT NULL,
    decision               varchar(10),
    used_at                timestamptz,
    created_at             timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT pk_integration_authorization_request PRIMARY KEY (id),
    CONSTRAINT fk_integration_authorization_request_redirect_uri FOREIGN KEY (client_id, redirect_uri)
        REFERENCES platform.integration_client_redirect_uri (client_id, redirect_uri) ON DELETE RESTRICT,
    CONSTRAINT ck_integration_authorization_request_request_uri_hash CHECK (request_uri_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_integration_authorization_request_shop CHECK (char_length(btrim(external_shop_id)) > 0
        AND char_length(btrim(shop_name)) > 0),
    CONSTRAINT ck_integration_authorization_request_code_challenge CHECK (code_challenge ~ '^[A-Za-z0-9_-]{43,128}$'),
    CONSTRAINT ck_integration_authorization_request_method CHECK (code_challenge_method = 'S256'),
    CONSTRAINT ck_integration_authorization_request_scopes CHECK (cardinality(scopes) >= 1
        AND scopes <@ ARRAY['catalog:read', 'catalog:match', 'price:propose', 'stock:read', 'orders:read',
            'orders:write']::varchar(30)[]),
    CONSTRAINT ck_integration_authorization_request_state CHECK (char_length(state) BETWEEN 32 AND 512),
    CONSTRAINT ck_integration_authorization_request_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_integration_authorization_request_decision CHECK (
        (decision IS NULL OR decision IN ('APPROVED', 'DENIED')) AND (decision IS NULL) = (used_at IS NULL))
);

CREATE UNIQUE INDEX uk_integration_authorization_request_request_uri_hash
    ON platform.integration_authorization_request (request_uri_hash);
CREATE INDEX ix_integration_authorization_request_client
    ON platform.integration_authorization_request (client_id, redirect_uri);
CREATE INDEX ix_integration_authorization_request_expires ON platform.integration_authorization_request (expires_at);

COMMENT ON TABLE platform.integration_authorization_request IS 'Demandes d''autorisation déposées par POST /oauth/par (RFC 9126, v14-D1) : request_uri à usage unique, 10 minutes, une seule décision.';
COMMENT ON COLUMN platform.integration_authorization_request.id IS 'Identifiant interne.';
COMMENT ON COLUMN platform.integration_authorization_request.request_uri_hash IS 'Empreinte SHA-256 (hexadécimal) du request_uri ; le request_uri n''est jamais stocké.';
COMMENT ON COLUMN platform.integration_authorization_request.client_id IS 'Client qui a déposé la demande.';
COMMENT ON COLUMN platform.integration_authorization_request.external_shop_id IS 'Identifiant de la boutique (User._id JeCréeMaBoutique) ; jamais un e-mail, un nom ni un slug.';
COMMENT ON COLUMN platform.integration_authorization_request.shop_name IS 'Nom de la boutique affiché sur l''écran de consentement (information seulement).';
COMMENT ON COLUMN platform.integration_authorization_request.redirect_uri IS 'URL de retour, obligatoirement présente dans la liste blanche du client (clé étrangère).';
COMMENT ON COLUMN platform.integration_authorization_request.code_challenge IS 'Défi PKCE : BASE64URL(SHA-256(code_verifier)).';
COMMENT ON COLUMN platform.integration_authorization_request.code_challenge_method IS 'Méthode PKCE : S256 uniquement.';
COMMENT ON COLUMN platform.integration_authorization_request.scopes IS 'Portées demandées.';
COMMENT ON COLUMN platform.integration_authorization_request.state IS 'Valeur opaque de JeCréeMaBoutique (32 à 512 caractères), renvoyée telle quelle à redirect_uri.';
COMMENT ON COLUMN platform.integration_authorization_request.expires_at IS 'Fin de validité du request_uri (10 minutes après le dépôt).';
COMMENT ON COLUMN platform.integration_authorization_request.decision IS 'APPROVED ou DENIED ; nul tant qu''aucune décision n''est prise.';
COMMENT ON COLUMN platform.integration_authorization_request.used_at IS 'Date de la décision (usage unique), renseignée si et seulement si decision l''est.';
COMMENT ON COLUMN platform.integration_authorization_request.created_at IS 'Date de dépôt.';

CREATE TABLE platform.integration_link (
    id                         uuid           NOT NULL DEFAULT gen_random_uuid(),
    client_id                  uuid           NOT NULL,
    company_id                 uuid           NOT NULL,
    external_shop_id           varchar(64)    NOT NULL,
    shop_name                  varchar(200)   NOT NULL,
    status                     varchar(20)    NOT NULL DEFAULT 'PENDING',
    suspension_reason          varchar(30),
    revocation_reason          varchar(30),
    currency                   varchar(3)     NOT NULL,
    scopes                     varchar(30)[]  NOT NULL,
    authorized_by              uuid           NOT NULL,
    authorized_by_username     varchar(50)    NOT NULL,
    secret_hash                varchar(64),
    pending_secret_hash        varchar(64),
    pending_secret_created_at  timestamptz,
    created_at                 timestamptz    NOT NULL DEFAULT now(),
    activated_at               timestamptz,
    suspended_at               timestamptz,
    revoked_at                 timestamptz,
    updated_at                 timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT pk_integration_link PRIMARY KEY (id),
    CONSTRAINT fk_integration_link_client FOREIGN KEY (client_id)
        REFERENCES platform.integration_client (id) ON DELETE RESTRICT,
    CONSTRAINT fk_integration_link_company FOREIGN KEY (company_id) REFERENCES platform.company (id) ON DELETE RESTRICT,
    CONSTRAINT ck_integration_link_shop CHECK (char_length(btrim(external_shop_id)) > 0
        AND char_length(btrim(shop_name)) > 0),
    CONSTRAINT ck_integration_link_status CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'REVOKED')),
    CONSTRAINT ck_integration_link_suspension_reason CHECK (suspension_reason IS NULL
        OR suspension_reason IN ('MANUAL', 'AUTHORIZER_LOST_ACCESS')),
    CONSTRAINT ck_integration_link_revocation_reason CHECK (revocation_reason IS NULL
        OR revocation_reason IN ('SHOP_REQUEST', 'GESTSTOCK_ADMIN', 'CODE_REPLAYED', 'EXPIRED')),
    CONSTRAINT ck_integration_link_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_integration_link_scopes CHECK (cardinality(scopes) >= 1
        AND scopes <@ ARRAY['catalog:read', 'catalog:match', 'price:propose', 'stock:read', 'orders:read',
            'orders:write']::varchar(30)[]),
    CONSTRAINT ck_integration_link_secret_format CHECK ((secret_hash IS NULL OR secret_hash ~ '^[0-9a-f]{64}$')
        AND (pending_secret_hash IS NULL OR pending_secret_hash ~ '^[0-9a-f]{64}$')),
    -- Motif de suspension si et seulement si SUSPENDED ; date de suspension renseignée pendant la suspension.
    CONSTRAINT ck_integration_link_suspended CHECK ((status = 'SUSPENDED') = (suspension_reason IS NOT NULL)
        AND (status <> 'SUSPENDED' OR suspended_at IS NOT NULL)),
    -- Date et motif de révocation si et seulement si REVOKED.
    CONSTRAINT ck_integration_link_revoked CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL)
        AND (status = 'REVOKED') = (revocation_reason IS NOT NULL)),
    -- activated_at : jamais en PENDING, toujours en ACTIVE ou SUSPENDED, jamais pour une liaison expirée sans activation.
    CONSTRAINT ck_integration_link_activated CHECK ((status <> 'PENDING' OR activated_at IS NULL)
        AND (status NOT IN ('ACTIVE', 'SUSPENDED') OR activated_at IS NOT NULL)
        AND (revocation_reason IS DISTINCT FROM 'EXPIRED' OR activated_at IS NULL)),
    -- Secret de liaison présent si et seulement si la liaison est utilisable (ACTIVE ou SUSPENDED) ; effacé à la révocation.
    CONSTRAINT ck_integration_link_secret CHECK ((status IN ('ACTIVE', 'SUSPENDED')) = (secret_hash IS NOT NULL)),
    -- Secret en attente de rotation : empreinte et date ensemble, seulement à côté d'un secret courant.
    CONSTRAINT ck_integration_link_pending_secret CHECK ((pending_secret_hash IS NULL) = (pending_secret_created_at IS NULL)
        AND (pending_secret_hash IS NULL OR secret_hash IS NOT NULL))
);

-- Unicité côté boutique (v14-D2) : au plus une liaison non révoquée par (client, boutique).
CREATE UNIQUE INDEX uk_integration_link_active_shop ON platform.integration_link (client_id, external_shop_id)
    WHERE status IN ('PENDING', 'ACTIVE', 'SUSPENDED');
CREATE INDEX ix_integration_link_client_shop ON platform.integration_link (client_id, external_shop_id);
CREATE INDEX ix_integration_link_company ON platform.integration_link (company_id, status);

COMMENT ON TABLE platform.integration_link IS 'Liaisons entre une entreprise GestStock et une boutique JeCréeMaBoutique : PENDING à l''approbation, ACTIVE à l''échange du code, SUSPENDED, REVOKED (définitif).';
COMMENT ON COLUMN platform.integration_link.id IS 'Identifiant de la liaison (sub du jeton de liaison, link_id du contrat).';
COMMENT ON COLUMN platform.integration_link.client_id IS 'Client JeCréeMaBoutique qui a demandé la liaison.';
COMMENT ON COLUMN platform.integration_link.company_id IS 'Entreprise liée (platform.company.id) : seule source de l''entreprise courante des appels de la liaison.';
COMMENT ON COLUMN platform.integration_link.external_shop_id IS 'Identifiant de la boutique (User._id JeCréeMaBoutique).';
COMMENT ON COLUMN platform.integration_link.shop_name IS 'Nom de la boutique au moment de l''autorisation (affichage).';
COMMENT ON COLUMN platform.integration_link.status IS 'PENDING, ACTIVE, SUSPENDED ou REVOKED.';
COMMENT ON COLUMN platform.integration_link.suspension_reason IS 'MANUAL ou AUTHORIZER_LOST_ACCESS (v14-D10), si et seulement si status = SUSPENDED.';
COMMENT ON COLUMN platform.integration_link.revocation_reason IS 'SHOP_REQUEST, GESTSTOCK_ADMIN, CODE_REPLAYED ou EXPIRED, si et seulement si status = REVOKED.';
COMMENT ON COLUMN platform.integration_link.currency IS 'Devise ISO 4217 de la liaison, normalisée depuis platform.company.currency à l''approbation (v11-D6).';
COMMENT ON COLUMN platform.integration_link.scopes IS 'Portées accordées.';
COMMENT ON COLUMN platform.integration_link.authorized_by IS 'Utilisateur de l''entreprise (app_user.id de sa base) qui a approuvé la liaison.';
COMMENT ON COLUMN platform.integration_link.authorized_by_username IS 'Nom d''utilisateur de la personne qui a approuvé (journal, affichage).';
COMMENT ON COLUMN platform.integration_link.secret_hash IS 'Empreinte SHA-256 du secret de liaison courant ; présente si et seulement si la liaison est ACTIVE ou SUSPENDED.';
COMMENT ON COLUMN platform.integration_link.pending_secret_hash IS 'Empreinte SHA-256 du nouveau secret pendant une rotation (v14-D6) ; l''ancien reste accepté jusqu''au premier usage du nouveau, 24 h au plus.';
COMMENT ON COLUMN platform.integration_link.pending_secret_created_at IS 'Date d''émission du secret en attente (point de départ des 24 h).';
COMMENT ON COLUMN platform.integration_link.created_at IS 'Date de création (approbation, statut PENDING).';
COMMENT ON COLUMN platform.integration_link.activated_at IS 'Date de l''échange du code (PENDING vers ACTIVE) ; nulle si la liaison n''a jamais été active.';
COMMENT ON COLUMN platform.integration_link.suspended_at IS 'Date de la dernière suspension.';
COMMENT ON COLUMN platform.integration_link.revoked_at IS 'Date de révocation, si et seulement si status = REVOKED.';
COMMENT ON COLUMN platform.integration_link.updated_at IS 'Date de dernière modification.';

CREATE TABLE platform.integration_authorization_code (
    id           uuid         NOT NULL DEFAULT gen_random_uuid(),
    code_hash    varchar(64)  NOT NULL,
    request_id   uuid         NOT NULL,
    link_id      uuid         NOT NULL,
    expires_at   timestamptz  NOT NULL,
    used_at      timestamptz,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_integration_authorization_code PRIMARY KEY (id),
    CONSTRAINT fk_integration_authorization_code_request FOREIGN KEY (request_id)
        REFERENCES platform.integration_authorization_request (id) ON DELETE RESTRICT,
    CONSTRAINT fk_integration_authorization_code_link FOREIGN KEY (link_id)
        REFERENCES platform.integration_link (id) ON DELETE RESTRICT,
    CONSTRAINT ck_integration_authorization_code_code_hash CHECK (code_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_integration_authorization_code_expiry CHECK (expires_at > created_at)
);

CREATE UNIQUE INDEX uk_integration_authorization_code_code_hash ON platform.integration_authorization_code (code_hash);
CREATE UNIQUE INDEX uk_integration_authorization_code_request ON platform.integration_authorization_code (request_id);
CREATE UNIQUE INDEX uk_integration_authorization_code_link ON platform.integration_authorization_code (link_id);
CREATE INDEX ix_integration_authorization_code_expires ON platform.integration_authorization_code (expires_at);

COMMENT ON TABLE platform.integration_authorization_code IS 'Codes d''autorisation (5 minutes, usage unique). Conservés après usage : un code rejoué est reconnu, refusé, et la liaison qu''il a créée est révoquée (v14-D14).';
COMMENT ON COLUMN platform.integration_authorization_code.id IS 'Identifiant interne.';
COMMENT ON COLUMN platform.integration_authorization_code.code_hash IS 'Empreinte SHA-256 (hexadécimal) du code ; le code n''est jamais stocké.';
COMMENT ON COLUMN platform.integration_authorization_code.request_id IS 'Demande d''autorisation approuvée (un code au plus par demande).';
COMMENT ON COLUMN platform.integration_authorization_code.link_id IS 'Liaison PENDING créée à l''approbation (un code au plus par liaison).';
COMMENT ON COLUMN platform.integration_authorization_code.expires_at IS 'Fin de validité (5 minutes après l''approbation).';
COMMENT ON COLUMN platform.integration_authorization_code.used_at IS 'Date du premier échange ; tout échange suivant est un rejeu.';
COMMENT ON COLUMN platform.integration_authorization_code.created_at IS 'Date d''émission.';

CREATE TABLE platform.integration_rate_limit (
    counter_key   varchar(200)  NOT NULL,
    window_start  timestamptz   NOT NULL,
    hits          integer       NOT NULL DEFAULT 0,
    CONSTRAINT pk_integration_rate_limit PRIMARY KEY (counter_key, window_start),
    CONSTRAINT ck_integration_rate_limit_key CHECK (char_length(counter_key) > 0),
    CONSTRAINT ck_integration_rate_limit_hits CHECK (hits >= 0)
);

CREATE INDEX ix_integration_rate_limit_window ON platform.integration_rate_limit (window_start);

COMMENT ON TABLE platform.integration_rate_limit IS 'Compteurs de débit en fenêtre fixe (C1, v13-D4) ; lignes expirées supprimées au fil des écritures. Jamais de secret dans la clé.';
COMMENT ON COLUMN platform.integration_rate_limit.counter_key IS 'Route et sujet du compteur (ex. oauth-token:client:<client_id>, oauth-token:ip:<ip>, link-secret:link:<id>).';
COMMENT ON COLUMN platform.integration_rate_limit.window_start IS 'Début de la fenêtre fixe.';
COMMENT ON COLUMN platform.integration_rate_limit.hits IS 'Nombre de requêtes comptées dans la fenêtre.';
