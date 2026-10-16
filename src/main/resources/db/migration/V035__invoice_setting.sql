-- =============================================================================
-- V035 — Configuration de la facture
--
-- Informations de l'entreprise, en-tête, pied de page et apparence des factures de vente :
-- une seule ligne (invoice_setting), globale à l'entreprise. Elle ne contient aucune donnée
-- propre à un dépôt : les factures de tous les dépôts présentent la même identité (option
-- show_warehouse pour afficher le dépôt de la vente).
--
-- Texte vide = information non configurée : jamais affichée sur une facture réelle.
-- Les valeurs par défaut sont celles de InvoiceSettings.defaults() (vérifié par un test).
--
-- Le logo (facultatif) est une image PNG ou JPEG stockée en base (invoice_logo, au plus une
-- ligne) : l'application ne dispose d'aucun autre stockage de fichiers.
--
-- Permission settings:manage (ADMIN) : modifier la configuration et le logo. La lecture suit
-- les droits d'affichage d'une facture (sale:read ou sale:create).
--
-- Annulation : db/rollback/U035__invoice_setting.sql
-- =============================================================================

CREATE TABLE invoice_setting (
    id                       uuid          NOT NULL DEFAULT gen_random_uuid(),
    singleton                boolean       NOT NULL DEFAULT true,

    -- Entreprise
    company_name             varchar(150)  NOT NULL DEFAULT '',
    legal_name               varchar(150)  NOT NULL DEFAULT '',
    slogan                   varchar(200)  NOT NULL DEFAULT '',
    address                  varchar(255)  NOT NULL DEFAULT '',
    city                     varchar(100)  NOT NULL DEFAULT '',
    country                  varchar(100)  NOT NULL DEFAULT '',
    phone                    varchar(50)   NOT NULL DEFAULT '',
    email                    varchar(254)  NOT NULL DEFAULT '',
    website                  varchar(200)  NOT NULL DEFAULT '',
    registration_number      varchar(100)  NOT NULL DEFAULT '',
    tax_id                   varchar(100)  NOT NULL DEFAULT '',

    -- En-tête
    show_logo                boolean       NOT NULL DEFAULT true,
    show_company_name        boolean       NOT NULL DEFAULT true,
    show_slogan              boolean       NOT NULL DEFAULT true,
    show_address             boolean       NOT NULL DEFAULT true,
    show_city_country        boolean       NOT NULL DEFAULT true,
    show_phone               boolean       NOT NULL DEFAULT true,
    show_email               boolean       NOT NULL DEFAULT true,
    show_website             boolean       NOT NULL DEFAULT true,
    show_registration_number boolean       NOT NULL DEFAULT true,
    show_tax_id              boolean       NOT NULL DEFAULT true,
    show_warehouse           boolean       NOT NULL DEFAULT false,
    logo_position            varchar(10)   NOT NULL DEFAULT 'LEFT',
    header_alignment         varchar(10)   NOT NULL DEFAULT 'RIGHT',
    header_separator         boolean       NOT NULL DEFAULT true,
    header_note              varchar(300)  NOT NULL DEFAULT '',

    -- Pied de page
    thank_you_message        varchar(300)  NOT NULL DEFAULT 'Merci pour votre confiance !',
    show_thank_you           boolean       NOT NULL DEFAULT true,
    sales_terms              varchar(1000) NOT NULL DEFAULT '',
    show_sales_terms         boolean       NOT NULL DEFAULT true,
    return_policy            varchar(1000) NOT NULL DEFAULT '',
    show_return_policy       boolean       NOT NULL DEFAULT true,
    payment_terms            varchar(500)  NOT NULL DEFAULT '',
    show_payment_terms       boolean       NOT NULL DEFAULT true,
    payment_details          varchar(500)  NOT NULL DEFAULT '',
    show_payment_details     boolean       NOT NULL DEFAULT true,
    promo_message            varchar(300)  NOT NULL DEFAULT '',
    show_promo_message       boolean       NOT NULL DEFAULT true,
    legal_text               varchar(1000) NOT NULL DEFAULT '',
    show_legal_text          boolean       NOT NULL DEFAULT true,
    signature_text           varchar(200)  NOT NULL DEFAULT '',
    show_signature           boolean       NOT NULL DEFAULT true,
    closing_text             varchar(300)  NOT NULL DEFAULT '',
    show_closing_text        boolean       NOT NULL DEFAULT true,
    footer_alignment         varchar(10)   NOT NULL DEFAULT 'CENTER',
    footer_separator         boolean       NOT NULL DEFAULT true,

    -- Apparence et affichage
    template                 varchar(10)   NOT NULL DEFAULT 'MODERN',
    primary_color            varchar(7)    NOT NULL DEFAULT '#1E3A8A',
    secondary_color          varchar(7)    NOT NULL DEFAULT '#F59E0B',
    text_color               varchar(7)    NOT NULL DEFAULT '#1F2937',
    date_format              varchar(15)   NOT NULL DEFAULT 'dd/MM/yyyy',
    currency_label           varchar(10)   NOT NULL DEFAULT 'FCFA',
    thousands_separator      varchar(10)   NOT NULL DEFAULT 'SPACE',
    decimals                 integer       NOT NULL DEFAULT 0,
    show_line_number         boolean       NOT NULL DEFAULT true,
    show_sku                 boolean       NOT NULL DEFAULT true,
    show_discount_column     boolean       NOT NULL DEFAULT true,
    show_amount_in_words     boolean       NOT NULL DEFAULT true,
    show_page_numbers        boolean       NOT NULL DEFAULT true,
    page_footer_text         varchar(200)  NOT NULL DEFAULT '',
    show_page_footer_text    boolean       NOT NULL DEFAULT true,

    version                  bigint        NOT NULL DEFAULT 0,
    created_at               timestamptz   NOT NULL DEFAULT now(),
    created_by               uuid,
    updated_at               timestamptz   NOT NULL DEFAULT now(),
    updated_by               uuid,
    CONSTRAINT pk_invoice_setting PRIMARY KEY (id),
    CONSTRAINT ck_invoice_setting_singleton CHECK (singleton),
    CONSTRAINT ck_invoice_setting_email CHECK (email = '' OR email ~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$'),
    CONSTRAINT ck_invoice_setting_logo_position CHECK (logo_position IN ('LEFT', 'RIGHT', 'TOP')),
    CONSTRAINT ck_invoice_setting_header_alignment CHECK (header_alignment IN ('LEFT', 'CENTER', 'RIGHT')),
    CONSTRAINT ck_invoice_setting_footer_alignment CHECK (footer_alignment IN ('LEFT', 'CENTER', 'RIGHT')),
    CONSTRAINT ck_invoice_setting_template CHECK (template IN ('CLASSIC', 'MODERN', 'COMPACT')),
    CONSTRAINT ck_invoice_setting_primary_color CHECK (primary_color ~ '^#[0-9A-F]{6}$'),
    CONSTRAINT ck_invoice_setting_secondary_color CHECK (secondary_color ~ '^#[0-9A-F]{6}$'),
    CONSTRAINT ck_invoice_setting_text_color CHECK (text_color ~ '^#[0-9A-F]{6}$'),
    CONSTRAINT ck_invoice_setting_date_format CHECK (date_format IN ('dd/MM/yyyy', 'dd-MM-yyyy', 'yyyy-MM-dd', 'd MMMM yyyy')),
    CONSTRAINT ck_invoice_setting_currency CHECK (currency_label IN ('FCFA', 'F CFA', 'XOF')),
    CONSTRAINT ck_invoice_setting_thousands CHECK (thousands_separator IN ('SPACE', 'DOT', 'COMMA', 'NONE')),
    CONSTRAINT ck_invoice_setting_decimals CHECK (decimals IN (0, 2))
);

CREATE UNIQUE INDEX uk_invoice_setting_singleton ON invoice_setting (singleton);

COMMENT ON TABLE invoice_setting IS 'Configuration des factures de vente (une seule ligne, globale à l''entreprise). Texte vide = non affiché.';
COMMENT ON COLUMN invoice_setting.registration_number IS 'Numéro d''enregistrement (RCCM).';
COMMENT ON COLUMN invoice_setting.tax_id IS 'Identifiant fiscal (IFU / NIF).';
COMMENT ON COLUMN invoice_setting.logo_position IS 'Logo à gauche des coordonnées, à droite, ou au-dessus (TOP).';
COMMENT ON COLUMN invoice_setting.date_format IS 'Motif de date (syntaxe DatePipe d''Angular) appliqué à la facture.';
COMMENT ON COLUMN invoice_setting.page_footer_text IS 'Mention imprimée en bas de chaque page du PDF.';

INSERT INTO invoice_setting DEFAULT VALUES;

CREATE TABLE invoice_logo (
    id           uuid         NOT NULL DEFAULT gen_random_uuid(),
    singleton    boolean      NOT NULL DEFAULT true,
    content_type varchar(20)  NOT NULL,
    data         bytea        NOT NULL,
    size_bytes   integer      NOT NULL,
    width        integer      NOT NULL,
    height       integer      NOT NULL,
    sha256       varchar(64)  NOT NULL,
    version      bigint       NOT NULL DEFAULT 0,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    created_by   uuid,
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    updated_by   uuid,
    CONSTRAINT pk_invoice_logo PRIMARY KEY (id),
    CONSTRAINT ck_invoice_logo_singleton CHECK (singleton),
    CONSTRAINT ck_invoice_logo_content_type CHECK (content_type IN ('image/png', 'image/jpeg')),
    CONSTRAINT ck_invoice_logo_size CHECK (size_bytes BETWEEN 1 AND 2097152 AND octet_length(data) = size_bytes),
    CONSTRAINT ck_invoice_logo_dimensions CHECK (width BETWEEN 1 AND 4000 AND height BETWEEN 1 AND 4000),
    CONSTRAINT ck_invoice_logo_sha256 CHECK (sha256 ~ '^[0-9a-f]{64}$')
);

CREATE UNIQUE INDEX uk_invoice_logo_singleton ON invoice_logo (singleton);

COMMENT ON TABLE invoice_logo IS 'Logo de l''entreprise sur les factures (au plus une ligne) : PNG ou JPEG de 2 Mo au plus.';

-- --- Permission : configurer les factures (administrateur) -------------------------------

INSERT INTO permission (code, description)
VALUES ('settings:manage', 'Configurer les factures et les informations de l''entreprise');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r CROSS JOIN permission p
WHERE r.code = 'ADMIN' AND p.code = 'settings:manage';
