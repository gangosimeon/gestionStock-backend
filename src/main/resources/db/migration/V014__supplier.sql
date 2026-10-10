-- =============================================================================
-- V014 — Fournisseurs
--
-- Contrat front : Supplier { id, name, phone?, email?, address?, deliveryLeadTimeDays }.
-- Ajouts : code (référence lisible, généré FOU-nnnn par séquence s'il n'est pas fourni),
-- interlocuteur, identifiant fiscal (NCC), délai de paiement (échéance des factures
-- fournisseurs, module achats), note, statut actif.
--
-- Relations :
--   * product.default_supplier_id reçoit enfin sa clé étrangère (annoncée par V011) ;
--     toute valeur orpheline est d'abord remise à NULL ;
--   * les achats (commandes, réceptions, factures, paiements, grand livre) référenceront
--     supplier(id) dans leurs propres migrations. Désactivation plutôt que suppression :
--     un fournisseur référencé ne disparaît jamais (ON DELETE RESTRICT).
--
-- Droits : le rôle CAISSIER reçoit supplier:read. L'écran Produits du front charge la liste
-- des fournisseurs pour afficher le fournisseur habituel : sans cette lecture, l'écran
-- échouerait pour les caissiers (le backend simulé l'autorisait à tout utilisateur connecté).
--
-- Annulation : db/rollback/U014__supplier.sql
-- =============================================================================

CREATE SEQUENCE seq_supplier_code;

CREATE TABLE supplier (
    id                      uuid          NOT NULL DEFAULT gen_random_uuid(),
    code                    varchar(20)   NOT NULL,
    name                    varchar(200)  NOT NULL,
    contact_name            varchar(150),
    phone                   varchar(30),
    email                   varchar(254),
    address                 varchar(400),
    tax_id                  varchar(50),
    delivery_lead_time_days integer       NOT NULL DEFAULT 0,
    payment_terms_days      integer       NOT NULL DEFAULT 0,
    note                    varchar(1000),
    active                  boolean       NOT NULL DEFAULT true,
    version                 bigint        NOT NULL DEFAULT 0,
    created_at              timestamptz   NOT NULL DEFAULT now(),
    created_by              uuid,
    updated_at              timestamptz   NOT NULL DEFAULT now(),
    updated_by              uuid,
    CONSTRAINT pk_supplier PRIMARY KEY (id),
    CONSTRAINT ck_supplier_code_format CHECK (code ~ '^[A-Z0-9][A-Z0-9-]{0,19}$'),
    CONSTRAINT ck_supplier_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_supplier_email_format CHECK (email IS NULL OR email ~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$'),
    CONSTRAINT ck_supplier_phone_format CHECK (phone IS NULL OR phone ~ '^[0-9+() .-]+$'),
    CONSTRAINT ck_supplier_delivery_lead_time CHECK (delivery_lead_time_days BETWEEN 0 AND 365),
    CONSTRAINT ck_supplier_payment_terms CHECK (payment_terms_days BETWEEN 0 AND 365)
);

-- Code stocké en majuscules (contrôle ck_supplier_code_format) ; nom unique sans tenir
-- compte de la casse pour éviter les doublons de saisie.
CREATE UNIQUE INDEX uk_supplier_code ON supplier (code);
CREATE UNIQUE INDEX uk_supplier_name ON supplier (lower(name));

COMMENT ON TABLE supplier IS 'Fournisseurs (laboratoires, distributeurs). Désactivés plutôt que supprimés.';
COMMENT ON COLUMN supplier.code IS 'Référence lisible, unique (FOU-0001 si non fournie).';
COMMENT ON COLUMN supplier.tax_id IS 'Identifiant fiscal du fournisseur (NCC en Côte d''Ivoire).';
COMMENT ON COLUMN supplier.delivery_lead_time_days IS 'Délai de livraison habituel, en jours (date de livraison prévue des commandes).';
COMMENT ON COLUMN supplier.payment_terms_days IS 'Délai de paiement accordé, en jours (0 = comptant) : échéance des factures.';

-- Fournisseur habituel des produits.
UPDATE product p
SET default_supplier_id = NULL
WHERE p.default_supplier_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM supplier s WHERE s.id = p.default_supplier_id);

ALTER TABLE product
    ADD CONSTRAINT fk_product_default_supplier FOREIGN KEY (default_supplier_id) REFERENCES supplier (id) ON DELETE RESTRICT;

COMMENT ON COLUMN product.default_supplier_id IS 'Fournisseur habituel (facultatif).';

-- Lecture des fournisseurs pour le rôle CAISSIER (voir en-tête).
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r CROSS JOIN permission p
WHERE r.code = 'CAISSIER' AND p.code = 'supplier:read'
ON CONFLICT DO NOTHING;
