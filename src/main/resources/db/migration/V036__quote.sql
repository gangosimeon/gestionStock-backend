-- =============================================================================
-- V036 — Devis
--
-- Un devis est une proposition commerciale : il ne réserve ni ne déplace aucun stock, ne
-- touche ni la caisse ni le compte du client. Il appartient au dépôt où il a été créé.
--
-- quote : en-tête (numéro DEV-AAAA-nnnnnn, dépôt, client, dates, champs facultatifs du
-- document, statut, total recalculé par le serveur). Le nom du client est recopié : un
-- renommage ultérieur ne modifie pas un devis déjà établi.
-- quote_line : lignes (produit existant du catalogue, libellé et référence recopiés, quantité,
-- prix unitaire retenu pour ce devis, total de ligne). Remplacées en bloc à chaque
-- modification du devis (tant qu'il est modifiable).
--
-- Statuts : DRAFT (brouillon), ISSUED (émis), ACCEPTED, REFUSED, INVOICED (facturé),
-- CANCELLED. « Expiré » n'est pas un statut enregistré : il se déduit d'un devis émis dont
-- la date de validité est passée (rien n'est modifié automatiquement).
--
-- Facturation : la facture d'un devis est une vente (module sale), enregistrée par
-- l'utilisateur à la caisse ; sale_id relie le devis à cette vente, au plus une fois
-- (uk_quote_sale_id : une vente ne facture qu'un devis).
--
-- Permissions quote:read et quote:manage : ADMIN et GESTIONNAIRE.
--
-- Annulation : db/rollback/U036__quote.sql
-- =============================================================================

CREATE SEQUENCE seq_quote_number;

CREATE TABLE quote (
    id                  uuid          NOT NULL DEFAULT gen_random_uuid(),
    number              varchar(30)   NOT NULL,
    warehouse_id        uuid          NOT NULL,
    customer_id         uuid          NOT NULL,
    customer_name       varchar(200)  NOT NULL,
    quote_date          date          NOT NULL,
    valid_until         date,
    subject             varchar(500),
    lot_reference       varchar(100),
    beneficiaries       integer,
    delivery_address    varchar(500),
    contract_reference  varchar(150),
    delivery_terms      varchar(1000),
    payment_terms       varchar(1000),
    execution_delay     varchar(200),
    notes               varchar(2000),
    status              varchar(20)   NOT NULL DEFAULT 'DRAFT',
    total               numeric(14,2) NOT NULL,
    sale_id             uuid,
    sale_number         varchar(30),
    invoiced_at         timestamptz,
    cancelled_at        timestamptz,
    cancellation_reason varchar(500),
    version             bigint        NOT NULL DEFAULT 0,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    created_by          uuid,
    updated_at          timestamptz   NOT NULL DEFAULT now(),
    updated_by          uuid,
    CONSTRAINT pk_quote PRIMARY KEY (id),
    CONSTRAINT fk_quote_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse (id) ON DELETE RESTRICT,
    CONSTRAINT fk_quote_customer FOREIGN KEY (customer_id) REFERENCES customer (id) ON DELETE RESTRICT,
    CONSTRAINT fk_quote_sale FOREIGN KEY (sale_id) REFERENCES sale (id) ON DELETE RESTRICT,
    CONSTRAINT ck_quote_status CHECK (status IN ('DRAFT', 'ISSUED', 'ACCEPTED', 'REFUSED', 'INVOICED', 'CANCELLED')),
    CONSTRAINT ck_quote_total CHECK (total >= 0),
    CONSTRAINT ck_quote_validity CHECK (valid_until IS NULL OR valid_until >= quote_date),
    CONSTRAINT ck_quote_beneficiaries CHECK (beneficiaries IS NULL OR beneficiaries > 0),
    -- Facturé ⇔ vente liée ; annulé ⇔ date d'annulation.
    CONSTRAINT ck_quote_invoiced CHECK ((status = 'INVOICED') = (sale_id IS NOT NULL)
                                        AND (sale_id IS NULL) = (invoiced_at IS NULL)
                                        AND (sale_id IS NULL) = (sale_number IS NULL)),
    CONSTRAINT ck_quote_cancellation CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL))
);

CREATE UNIQUE INDEX uk_quote_number ON quote (number);
CREATE UNIQUE INDEX uk_quote_sale_id ON quote (sale_id);
CREATE INDEX ix_quote_warehouse_id_quote_date ON quote (warehouse_id, quote_date DESC);
CREATE INDEX ix_quote_customer_id ON quote (customer_id);

COMMENT ON TABLE quote IS 'Devis : proposition commerciale, sans effet sur le stock, la caisse ni le compte client.';
COMMENT ON COLUMN quote.number IS 'Numéro lisible DEV-AAAA-nnnnnn (séquence seq_quote_number).';
COMMENT ON COLUMN quote.customer_name IS 'Nom du client à la date du devis (instantané).';
COMMENT ON COLUMN quote.quote_date IS 'Date du document (modifiable, une date passée est admise).';
COMMENT ON COLUMN quote.total IS 'Somme des lignes, recalculée par le serveur.';
COMMENT ON COLUMN quote.sale_id IS 'Vente (facture) issue du devis, enregistrée à la caisse.';

CREATE TABLE quote_line (
    id           uuid          NOT NULL DEFAULT gen_random_uuid(),
    quote_id     uuid          NOT NULL,
    line_number  integer       NOT NULL,
    product_id   uuid          NOT NULL,
    product_sku  varchar(50)   NOT NULL,
    product_name varchar(200)  NOT NULL,
    quantity     integer       NOT NULL,
    unit_price   numeric(14,2) NOT NULL,
    line_total   numeric(14,2) NOT NULL,
    CONSTRAINT pk_quote_line PRIMARY KEY (id),
    CONSTRAINT fk_quote_line_quote FOREIGN KEY (quote_id) REFERENCES quote (id) ON DELETE CASCADE,
    CONSTRAINT fk_quote_line_product FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE RESTRICT,
    CONSTRAINT ck_quote_line_line_number CHECK (line_number > 0),
    CONSTRAINT ck_quote_line_quantity CHECK (quantity > 0),
    CONSTRAINT ck_quote_line_unit_price CHECK (unit_price > 0),
    CONSTRAINT ck_quote_line_total CHECK (line_total = quantity * unit_price)
);

CREATE UNIQUE INDEX uk_quote_line_number ON quote_line (quote_id, line_number);
CREATE UNIQUE INDEX uk_quote_line_product ON quote_line (quote_id, product_id);
CREATE INDEX ix_quote_line_product_id ON quote_line (product_id);

COMMENT ON TABLE quote_line IS 'Lignes de devis : produit du catalogue, libellé et prix retenus recopiés à l''enregistrement.';
COMMENT ON COLUMN quote_line.unit_price IS 'Prix unitaire retenu pour ce devis (prérempli avec le tarif du produit, modifiable).';

-- --- Permissions : devis (ADMIN, GESTIONNAIRE) ---------------------------------------------

INSERT INTO permission (code, description) VALUES
    ('quote:read', 'Consulter les devis'),
    ('quote:manage', 'Créer, modifier, émettre et facturer les devis');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r CROSS JOIN permission p
WHERE r.code IN ('ADMIN', 'GESTIONNAIRE') AND p.code IN ('quote:read', 'quote:manage');
