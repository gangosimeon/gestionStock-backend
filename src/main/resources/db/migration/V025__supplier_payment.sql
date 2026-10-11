-- V025 — Paiements fournisseurs (règlements des dettes d'achat).
--
-- Règles (docs/supplier-payments.md) :
--   * un paiement est réparti sur une ou plusieurs commandes du fournisseur
--     (supplier_payment_allocation) : la commande désignée, sinon les plus anciennes ayant un
--     reste à payer. Un seul solde, par commande comme par fournisseur ;
--   * montant dû d'une commande : total commandé tant qu'elle est en cours ou reçue (acompte
--     possible), valeur reçue une fois soldée, rien si annulée. Jamais de trop-payé ;
--   * purchase_order.paid_amount = somme des imputations (cache tenu par le serveur, vérifié
--     au COMMIT) ;
--   * paiement en espèces = décaissement SUPPLIER_PAYMENT dans la caisse ouverte de l'auteur ;
--     virement et mobile money n'y passent pas ;
--   * paiements et imputations en ajout seul.

-- --- Commande : montant payé --------------------------------------------------------------------

ALTER TABLE purchase_order ADD COLUMN paid_amount numeric(14,2) NOT NULL DEFAULT 0;
ALTER TABLE purchase_order ADD CONSTRAINT ck_purchase_order_paid_amount CHECK (
    paid_amount >= 0
    AND paid_amount <= total_amount
    -- Soldée : seule la valeur reçue est due ; annulée (rien reçu) : rien n'est dû.
    AND (status NOT IN ('CLOSED', 'CANCELLED') OR paid_amount <= received_amount));

COMMENT ON COLUMN purchase_order.paid_amount IS
    'Montant réglé au fournisseur sur cette commande (somme de supplier_payment_allocation).';

-- --- Paiement ------------------------------------------------------------------------------------

CREATE SEQUENCE seq_supplier_payment_number;

CREATE TABLE supplier_payment (
    id                 uuid          NOT NULL DEFAULT gen_random_uuid(),
    number             varchar(30)   NOT NULL,
    supplier_id        uuid          NOT NULL,
    payment_date       date          NOT NULL,
    amount             numeric(14,2) NOT NULL,
    payment_method     varchar(20)   NOT NULL,
    external_reference varchar(50),
    note               varchar(500),
    created_at         timestamptz   NOT NULL DEFAULT now(),
    created_by         uuid,
    CONSTRAINT pk_supplier_payment PRIMARY KEY (id),
    CONSTRAINT fk_supplier_payment_supplier FOREIGN KEY (supplier_id) REFERENCES supplier (id) ON DELETE RESTRICT,
    CONSTRAINT ck_supplier_payment_amount CHECK (amount > 0),
    CONSTRAINT ck_supplier_payment_method CHECK (payment_method IN ('CASH', 'MOBILE_MONEY', 'BANK_TRANSFER'))
);

CREATE UNIQUE INDEX uk_supplier_payment_number ON supplier_payment (number);
CREATE INDEX ix_supplier_payment_supplier_id ON supplier_payment (supplier_id, payment_date DESC, created_at DESC);

COMMENT ON TABLE supplier_payment IS 'Paiements aux fournisseurs, en ajout seul.';
COMMENT ON COLUMN supplier_payment.number IS 'Référence PAF-AAAA-nnnnnn (séquence seq_supplier_payment_number).';
COMMENT ON COLUMN supplier_payment.payment_date IS 'Date du règlement (jamais dans le futur).';
COMMENT ON COLUMN supplier_payment.external_reference IS 'Référence du virement, du chèque ou de la transaction mobile.';

CREATE TABLE supplier_payment_allocation (
    payment_id        uuid          NOT NULL,
    purchase_order_id uuid          NOT NULL,
    amount            numeric(14,2) NOT NULL,
    CONSTRAINT pk_supplier_payment_allocation PRIMARY KEY (payment_id, purchase_order_id),
    CONSTRAINT fk_supplier_payment_allocation_payment FOREIGN KEY (payment_id)
        REFERENCES supplier_payment (id) ON DELETE RESTRICT,
    CONSTRAINT fk_supplier_payment_allocation_order FOREIGN KEY (purchase_order_id)
        REFERENCES purchase_order (id) ON DELETE RESTRICT,
    CONSTRAINT ck_supplier_payment_allocation_amount CHECK (amount > 0)
);

CREATE INDEX ix_supplier_payment_allocation_order ON supplier_payment_allocation (purchase_order_id);

COMMENT ON TABLE supplier_payment_allocation IS 'Imputation d''un paiement fournisseur sur ses commandes.';

CREATE TRIGGER trg_supplier_payment_prevent_modification
    BEFORE UPDATE OR DELETE ON supplier_payment
    FOR EACH ROW EXECUTE FUNCTION fn_prevent_modification();
CREATE TRIGGER trg_supplier_payment_prevent_truncate
    BEFORE TRUNCATE ON supplier_payment
    FOR EACH STATEMENT EXECUTE FUNCTION fn_prevent_modification();
CREATE TRIGGER trg_supplier_payment_allocation_prevent_modification
    BEFORE UPDATE OR DELETE ON supplier_payment_allocation
    FOR EACH ROW EXECUTE FUNCTION fn_prevent_modification();
CREATE TRIGGER trg_supplier_payment_allocation_prevent_truncate
    BEFORE TRUNCATE ON supplier_payment_allocation
    FOR EACH STATEMENT EXECUTE FUNCTION fn_prevent_modification();

-- --- Caisse : décaissement d'un paiement fournisseur --------------------------------------------

ALTER TABLE cash_entry DROP CONSTRAINT ck_cash_entry_type;
ALTER TABLE cash_entry DROP CONSTRAINT ck_cash_entry_direction;
ALTER TABLE cash_entry DROP CONSTRAINT ck_cash_entry_source;

ALTER TABLE cash_entry ADD CONSTRAINT ck_cash_entry_type CHECK (entry_type IN (
    'SALE', 'CUSTOMER_PAYMENT', 'CASH_IN', 'CORRECTION_IN',
    'SALE_REFUND', 'RETURN_REFUND', 'CASH_OUT', 'CORRECTION_OUT', 'SUPPLIER_PAYMENT'));
ALTER TABLE cash_entry ADD CONSTRAINT ck_cash_entry_direction CHECK (
    (entry_type IN ('SALE', 'CUSTOMER_PAYMENT', 'CASH_IN', 'CORRECTION_IN') AND direction = 'IN')
    OR (entry_type IN ('SALE_REFUND', 'RETURN_REFUND', 'CASH_OUT', 'CORRECTION_OUT', 'SUPPLIER_PAYMENT')
        AND direction = 'OUT'));
ALTER TABLE cash_entry ADD CONSTRAINT ck_cash_entry_source CHECK (
    entry_type NOT IN ('SALE', 'SALE_REFUND', 'RETURN_REFUND', 'CUSTOMER_PAYMENT', 'SUPPLIER_PAYMENT')
    OR (source_type IS NOT NULL AND source_id IS NOT NULL));
-- Un paiement fournisseur en caisse est toujours en espèces.
ALTER TABLE cash_entry ADD CONSTRAINT ck_cash_entry_supplier_payment_cash CHECK (
    entry_type <> 'SUPPLIER_PAYMENT' OR payment_method = 'CASH');

-- --- Cohérence (vérifiée au COMMIT) -------------------------------------------------------------

CREATE FUNCTION fn_supplier_payment_consistency() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    p   supplier_payment%ROWTYPE;
    allocated numeric(14,2);
BEGIN
    SELECT * INTO p FROM supplier_payment WHERE id = NEW.id;
    SELECT coalesce(sum(a.amount), 0) INTO allocated
    FROM supplier_payment_allocation a WHERE a.payment_id = p.id;
    IF allocated <> p.amount THEN
        RAISE EXCEPTION 'supplier_payment % : % imputé(s) sur les commandes pour un paiement de %.',
            p.number, allocated, p.amount USING ERRCODE = 'check_violation';
    END IF;
    IF EXISTS (SELECT 1 FROM supplier_payment_allocation a JOIN purchase_order o ON o.id = a.purchase_order_id
               WHERE a.payment_id = p.id AND o.supplier_id <> p.supplier_id) THEN
        RAISE EXCEPTION 'supplier_payment % : imputé sur la commande d''un autre fournisseur.', p.number
            USING ERRCODE = 'check_violation';
    END IF;
    IF (p.payment_method = 'CASH') <> EXISTS (
            SELECT 1 FROM cash_entry c
            WHERE c.entry_type = 'SUPPLIER_PAYMENT' AND c.source_type = 'SUPPLIER_PAYMENT'
              AND c.source_id = p.id AND c.amount = p.amount) THEN
        RAISE EXCEPTION 'supplier_payment % : un paiement en espèces (et lui seul) sort de la caisse.', p.number
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

COMMENT ON FUNCTION fn_supplier_payment_consistency() IS
    'Contrainte différée : paiement entièrement imputé sur ses commandes ; espèces ⇔ décaissement en caisse.';

CREATE CONSTRAINT TRIGGER trg_supplier_payment_consistency
    AFTER INSERT ON supplier_payment
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION fn_supplier_payment_consistency();

-- purchase_order.paid_amount reste égal à la somme de ses imputations.
CREATE FUNCTION fn_purchase_order_paid_consistency() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    order_id uuid;
    paid     numeric(14,2);
    stored   numeric(14,2);
    num      varchar(30);
BEGIN
    -- Deux branches : NEW n'a pas les mêmes champs selon la table du trigger.
    IF TG_TABLE_NAME = 'purchase_order' THEN
        order_id := NEW.id;
    ELSE
        order_id := NEW.purchase_order_id;
    END IF;
    SELECT o.paid_amount, o.number INTO stored, num FROM purchase_order o WHERE o.id = order_id;
    SELECT coalesce(sum(a.amount), 0) INTO paid FROM supplier_payment_allocation a WHERE a.purchase_order_id = order_id;
    IF paid <> stored THEN
        RAISE EXCEPTION 'purchase_order % : montant payé % mais % imputé(s) par les paiements.', num, stored, paid
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

COMMENT ON FUNCTION fn_purchase_order_paid_consistency() IS
    'Contrainte différée : purchase_order.paid_amount = somme des imputations de paiements.';

CREATE CONSTRAINT TRIGGER trg_supplier_payment_allocation_paid_consistency
    AFTER INSERT ON supplier_payment_allocation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION fn_purchase_order_paid_consistency();

CREATE CONSTRAINT TRIGGER trg_purchase_order_paid_consistency
    AFTER UPDATE OF paid_amount ON purchase_order
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION fn_purchase_order_paid_consistency();
