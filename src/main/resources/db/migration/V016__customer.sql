-- =============================================================================
-- V016 — Clients et compte client (créances)
--
-- customer : contrat front Customer { id, name, phone?, email?, creditLimit } complété de
-- l'identité (code CLI-nnnnnn, particulier ou entreprise, NCC), de l'adresse et des
-- conditions commerciales (catégorie de prix détail/gros, plafond de crédit, délai de
-- paiement). Désactivé plutôt que supprimé : ventes et paiements le référencent.
--
-- customer_account_entry : relevé du compte client (créances), en ajout seul. Ce n'est PAS
-- une comptabilité : uniquement ce que le client doit au magasin.
--   DEBIT  : CREDIT_SALE        reste dû d'une vente à crédit (module ventes)
--   CREDIT : PAYMENT            règlement du client (module paiements / caisse)
--            CREDIT_NOTE        avoir imputé sur la dette (module retours)
--            SALE_CANCELLATION  annulation d'une vente à crédit (module ventes)
-- Solde dû = somme des débits − somme des crédits ; jamais négatif (pas de trop-perçu) et
-- plafonné par credit_limit pour les nouveaux débits : contrôles sous verrou du client
-- (CustomerAccountService). Une même pièce ne peut être imputée deux fois (même type).
--
-- Droits : nouvelle permission customer:credit (accorder ou modifier un plafond de crédit),
-- distincte de customer:update que détient le caissier : l'audit relevait que n'importe quel
-- utilisateur pouvait s'accorder un crédit.
--
-- Annulation : db/rollback/U016__customer.sql
-- =============================================================================

CREATE SEQUENCE seq_customer_code;

CREATE TABLE customer (
    id                 uuid          NOT NULL DEFAULT gen_random_uuid(),
    code               varchar(20)   NOT NULL,
    type               varchar(20)   NOT NULL DEFAULT 'INDIVIDUAL',
    name               varchar(200)  NOT NULL,
    phone              varchar(30),
    email              varchar(254),
    address            varchar(400),
    city               varchar(100),
    tax_id             varchar(50),
    price_category     varchar(20)   NOT NULL DEFAULT 'RETAIL',
    credit_limit       numeric(14,2) NOT NULL DEFAULT 0,
    payment_terms_days integer       NOT NULL DEFAULT 0,
    note               varchar(1000),
    active             boolean       NOT NULL DEFAULT true,
    version            bigint        NOT NULL DEFAULT 0,
    created_at         timestamptz   NOT NULL DEFAULT now(),
    created_by         uuid,
    updated_at         timestamptz   NOT NULL DEFAULT now(),
    updated_by         uuid,
    CONSTRAINT pk_customer PRIMARY KEY (id),
    CONSTRAINT ck_customer_code_format CHECK (code ~ '^[A-Z0-9][A-Z0-9-]{0,19}$'),
    CONSTRAINT ck_customer_type CHECK (type IN ('INDIVIDUAL', 'COMPANY')),
    CONSTRAINT ck_customer_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_customer_email_format CHECK (email IS NULL OR email ~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$'),
    CONSTRAINT ck_customer_phone_format CHECK (phone IS NULL OR phone ~ '^[0-9+() .-]+$'),
    CONSTRAINT ck_customer_price_category CHECK (price_category IN ('RETAIL', 'WHOLESALE')),
    CONSTRAINT ck_customer_credit_limit CHECK (credit_limit >= 0),
    CONSTRAINT ck_customer_payment_terms CHECK (payment_terms_days BETWEEN 0 AND 365)
);

-- Pas d'unicité sur le nom ni le téléphone : homonymes et téléphone familial sont fréquents.
CREATE UNIQUE INDEX uk_customer_code ON customer (code);
CREATE INDEX ix_customer_name ON customer (lower(name));
CREATE INDEX ix_customer_phone ON customer (phone);

COMMENT ON TABLE customer IS 'Clients (particuliers, entreprises, institutions). Désactivés plutôt que supprimés.';
COMMENT ON COLUMN customer.code IS 'Référence lisible, unique (CLI-000001 si non fournie).';
COMMENT ON COLUMN customer.price_category IS 'Tarif proposé par défaut à la vente : RETAIL (détail) ou WHOLESALE (gros).';
COMMENT ON COLUMN customer.credit_limit IS 'Plafond de la dette autorisée ; 0 = aucun crédit. Modifiable avec customer:credit.';
COMMENT ON COLUMN customer.payment_terms_days IS 'Délai de paiement accordé sur les ventes à crédit, en jours.';

CREATE TABLE customer_account_entry (
    id               uuid          NOT NULL DEFAULT gen_random_uuid(),
    customer_id      uuid          NOT NULL,
    entry_type       varchar(30)   NOT NULL,
    direction        varchar(6)    NOT NULL,
    amount           numeric(14,2) NOT NULL,
    balance_after    numeric(14,2) NOT NULL,
    source_type      varchar(30)   NOT NULL,
    source_id        uuid          NOT NULL,
    source_reference varchar(100),
    note             varchar(500),
    created_at       timestamptz   NOT NULL DEFAULT now(),
    created_by       uuid          NOT NULL,
    CONSTRAINT pk_customer_account_entry PRIMARY KEY (id),
    CONSTRAINT fk_customer_account_entry_customer FOREIGN KEY (customer_id) REFERENCES customer (id) ON DELETE RESTRICT,
    CONSTRAINT fk_customer_account_entry_created_by FOREIGN KEY (created_by) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_customer_account_entry_type CHECK (entry_type IN ('CREDIT_SALE', 'PAYMENT', 'CREDIT_NOTE', 'SALE_CANCELLATION')),
    -- Sens imposé par le type : seule la vente à crédit augmente la dette.
    CONSTRAINT ck_customer_account_entry_direction CHECK (
        (entry_type = 'CREDIT_SALE' AND direction = 'DEBIT')
        OR (entry_type IN ('PAYMENT', 'CREDIT_NOTE', 'SALE_CANCELLATION') AND direction = 'CREDIT')),
    CONSTRAINT ck_customer_account_entry_amount CHECK (amount > 0),
    CONSTRAINT ck_customer_account_entry_balance_after CHECK (balance_after >= 0),
    CONSTRAINT ck_customer_account_entry_source_type_format CHECK (source_type ~ '^[A-Z][A-Z0-9_]*$')
);

CREATE INDEX ix_customer_account_entry_customer_id ON customer_account_entry (customer_id, created_at DESC);
CREATE INDEX ix_customer_account_entry_created_by ON customer_account_entry (created_by);
-- Une pièce (vente, paiement, avoir) n'est imputée qu'une fois pour un même type d'écriture.
CREATE UNIQUE INDEX uk_customer_account_entry_source ON customer_account_entry (source_type, source_id, entry_type);

CREATE TRIGGER trg_customer_account_entry_prevent_modification
    BEFORE UPDATE OR DELETE ON customer_account_entry
    FOR EACH ROW EXECUTE FUNCTION fn_prevent_modification();
CREATE TRIGGER trg_customer_account_entry_prevent_truncate
    BEFORE TRUNCATE ON customer_account_entry
    FOR EACH STATEMENT EXECUTE FUNCTION fn_prevent_modification();

COMMENT ON TABLE customer_account_entry IS 'Relevé du compte client (créances), en ajout seul. Solde dû = débits − crédits.';
COMMENT ON COLUMN customer_account_entry.balance_after IS 'Solde dû par le client après cette écriture (calculé sous verrou du client).';
COMMENT ON COLUMN customer_account_entry.source_type IS 'Pièce à l''origine de l''écriture : SALE, CUSTOMER_PAYMENT, CUSTOMER_RETURN…';

-- --- Permission : accorder un crédit ------------------------------------------------------

INSERT INTO permission (code, description)
VALUES ('customer:credit', 'Accorder ou modifier le plafond de crédit des clients');

UPDATE permission SET description = 'Modifier les clients (hors plafond de crédit)' WHERE code = 'customer:update';

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r CROSS JOIN permission p
WHERE r.code IN ('ADMIN', 'GESTIONNAIRE') AND p.code = 'customer:credit';
