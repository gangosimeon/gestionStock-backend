-- =============================================================================
-- V027 — Dépenses (frais de fonctionnement d'un dépôt).
--
-- Règles (docs/expenses.md) :
--   * une dépense appartient à un dépôt, a une catégorie fermée, un libellé, un montant > 0,
--     une date (jamais dans le futur, contrôlé par le serveur) et un mode de paiement ;
--   * en espèces, elle sort de la caisse ouverte de son auteur dans ce dépôt : écriture de
--     caisse EXPENSE (sortie), refusée si le tiroir n'a pas assez d'espèces. Mobile money,
--     virement et chèque ne touchent pas la caisse ;
--   * une dépense ne se modifie pas et ne se supprime pas : une erreur se corrige par
--     annulation (motif obligatoire). Une dépense en espèces annulée revient dans la caisse
--     ouverte de la personne qui annule (écriture EXPENSE_CANCELLATION, entrée) ;
--   * une dépense annulée reste visible mais sort des totaux.
--
-- Annulation : db/rollback/U027__expense.sql
-- =============================================================================

CREATE SEQUENCE seq_expense_number;

CREATE TABLE expense (
    id                  uuid          NOT NULL DEFAULT gen_random_uuid(),
    number              varchar(30)   NOT NULL,
    warehouse_id        uuid          NOT NULL,
    category            varchar(30)   NOT NULL,
    label               varchar(200)  NOT NULL,
    amount              numeric(14,2) NOT NULL,
    expense_date        date          NOT NULL,
    payment_method      varchar(20)   NOT NULL,
    external_reference  varchar(50),
    note                varchar(500),
    status              varchar(20)   NOT NULL DEFAULT 'PAID',
    cancelled_at        timestamptz,
    cancelled_by        uuid,
    cancellation_reason varchar(500),
    version             bigint        NOT NULL DEFAULT 0,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    created_by          uuid,
    updated_at          timestamptz   NOT NULL DEFAULT now(),
    updated_by          uuid,
    CONSTRAINT pk_expense PRIMARY KEY (id),
    CONSTRAINT fk_expense_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse (id) ON DELETE RESTRICT,
    CONSTRAINT ck_expense_category CHECK (category IN (
        'TRANSPORT', 'SALARY', 'RENT', 'UTILITIES', 'SUPPLIES', 'MAINTENANCE', 'TAXES', 'OTHER')),
    CONSTRAINT ck_expense_label_not_blank CHECK (btrim(label) <> ''),
    CONSTRAINT ck_expense_amount CHECK (amount > 0),
    CONSTRAINT ck_expense_payment_method CHECK (payment_method IN ('CASH', 'MOBILE_MONEY', 'BANK_TRANSFER', 'CHEQUE')),
    CONSTRAINT ck_expense_status CHECK (status IN ('PAID', 'CANCELLED')),
    CONSTRAINT ck_expense_cancellation CHECK (
        (status = 'CANCELLED') = (cancelled_at IS NOT NULL)
        AND (status = 'CANCELLED') = (cancellation_reason IS NOT NULL))
);

CREATE UNIQUE INDEX uk_expense_number ON expense (number);
CREATE INDEX ix_expense_warehouse_id_expense_date ON expense (warehouse_id, expense_date DESC, created_at DESC);

COMMENT ON TABLE expense IS 'Dépenses de fonctionnement d''un dépôt ; jamais modifiées ni supprimées, seulement annulées.';
COMMENT ON COLUMN expense.number IS 'Référence DEP-AAAA-nnnnnn (séquence seq_expense_number).';
COMMENT ON COLUMN expense.expense_date IS 'Date de la dépense (jamais dans le futur).';
COMMENT ON COLUMN expense.payment_method IS 'CASH : sortie de la caisse ouverte de l''auteur ; autres modes : hors caisse.';
COMMENT ON COLUMN expense.external_reference IS 'Référence du virement, du chèque, de la transaction mobile ou du reçu.';

CREATE TRIGGER trg_expense_prevent_delete
    BEFORE DELETE ON expense
    FOR EACH ROW EXECUTE FUNCTION fn_prevent_modification();
CREATE TRIGGER trg_expense_prevent_truncate
    BEFORE TRUNCATE ON expense
    FOR EACH STATEMENT EXECUTE FUNCTION fn_prevent_modification();

CREATE FUNCTION fn_expense_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.number <> OLD.number
       OR NEW.warehouse_id <> OLD.warehouse_id
       OR NEW.category <> OLD.category
       OR NEW.label <> OLD.label
       OR NEW.amount <> OLD.amount
       OR NEW.expense_date <> OLD.expense_date
       OR NEW.payment_method <> OLD.payment_method
       OR NEW.external_reference IS DISTINCT FROM OLD.external_reference
       OR NEW.note IS DISTINCT FROM OLD.note
       OR NEW.created_at <> OLD.created_at
       OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
        RAISE EXCEPTION 'expense % : une dépense enregistrée ne se modifie pas (seule l''annulation est permise).',
            OLD.number USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.status <> OLD.status AND NOT (OLD.status = 'PAID' AND NEW.status = 'CANCELLED') THEN
        RAISE EXCEPTION 'expense % : passage de statut % → % interdit.', OLD.number, OLD.status, NEW.status
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.status = 'CANCELLED' AND (
            NEW.cancelled_at IS DISTINCT FROM OLD.cancelled_at
            OR NEW.cancelled_by IS DISTINCT FROM OLD.cancelled_by
            OR NEW.cancellation_reason IS DISTINCT FROM OLD.cancellation_reason) THEN
        RAISE EXCEPTION 'expense % : annulation déjà enregistrée.', OLD.number USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION fn_expense_guard() IS 'Trigger de expense : dépense figée, seule l''annulation (une fois) est permise.';

CREATE TRIGGER trg_expense_guard
    BEFORE UPDATE ON expense
    FOR EACH ROW EXECUTE FUNCTION fn_expense_guard();

-- --- Caisse : sortie d'une dépense et son retour à l'annulation ---------------------------------

ALTER TABLE cash_entry DROP CONSTRAINT ck_cash_entry_type;
ALTER TABLE cash_entry DROP CONSTRAINT ck_cash_entry_direction;
ALTER TABLE cash_entry DROP CONSTRAINT ck_cash_entry_source;

ALTER TABLE cash_entry ADD CONSTRAINT ck_cash_entry_type CHECK (entry_type IN (
    'SALE', 'CUSTOMER_PAYMENT', 'CASH_IN', 'CORRECTION_IN', 'EXPENSE_CANCELLATION',
    'SALE_REFUND', 'RETURN_REFUND', 'CASH_OUT', 'CORRECTION_OUT', 'SUPPLIER_PAYMENT', 'EXPENSE'));
ALTER TABLE cash_entry ADD CONSTRAINT ck_cash_entry_direction CHECK (
    (entry_type IN ('SALE', 'CUSTOMER_PAYMENT', 'CASH_IN', 'CORRECTION_IN', 'EXPENSE_CANCELLATION') AND direction = 'IN')
    OR (entry_type IN ('SALE_REFUND', 'RETURN_REFUND', 'CASH_OUT', 'CORRECTION_OUT', 'SUPPLIER_PAYMENT', 'EXPENSE')
        AND direction = 'OUT'));
ALTER TABLE cash_entry ADD CONSTRAINT ck_cash_entry_source CHECK (
    entry_type NOT IN ('SALE', 'SALE_REFUND', 'RETURN_REFUND', 'CUSTOMER_PAYMENT', 'SUPPLIER_PAYMENT', 'EXPENSE',
                       'EXPENSE_CANCELLATION')
    OR (source_type IS NOT NULL AND source_id IS NOT NULL));
-- Une dépense passe en caisse uniquement en espèces.
ALTER TABLE cash_entry ADD CONSTRAINT ck_cash_entry_expense_cash CHECK (
    entry_type NOT IN ('EXPENSE', 'EXPENSE_CANCELLATION') OR payment_method = 'CASH');

-- --- Cohérence dépense ⇔ caisse (vérifiée au COMMIT) --------------------------------------------

CREATE FUNCTION fn_expense_cash_consistency() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    e expense%ROWTYPE;
BEGIN
    SELECT * INTO e FROM expense WHERE id = NEW.id;
    IF (e.payment_method = 'CASH') <> EXISTS (
            SELECT 1 FROM cash_entry c
            WHERE c.entry_type = 'EXPENSE' AND c.source_type = 'EXPENSE' AND c.source_id = e.id
              AND c.amount = e.amount) THEN
        RAISE EXCEPTION 'expense % : une dépense en espèces (et elle seule) sort de la caisse.', e.number
            USING ERRCODE = 'check_violation';
    END IF;
    IF (e.payment_method = 'CASH' AND e.status = 'CANCELLED') <> EXISTS (
            SELECT 1 FROM cash_entry c
            WHERE c.entry_type = 'EXPENSE_CANCELLATION' AND c.source_type = 'EXPENSE' AND c.source_id = e.id
              AND c.amount = e.amount) THEN
        RAISE EXCEPTION 'expense % : une dépense en espèces annulée (et elle seule) revient en caisse.', e.number
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

COMMENT ON FUNCTION fn_expense_cash_consistency() IS
    'Contrainte différée : espèces ⇔ sortie de caisse ; espèces annulées ⇔ retour en caisse.';

CREATE CONSTRAINT TRIGGER trg_expense_cash_consistency
    AFTER INSERT OR UPDATE OF status ON expense
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION fn_expense_cash_consistency();

-- --- Permission : expense:delete devient l'annulation -------------------------------------------

UPDATE permission SET description = 'Annuler des dépenses (motif obligatoire)' WHERE code = 'expense:delete';
