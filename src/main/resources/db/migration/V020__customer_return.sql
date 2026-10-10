-- =============================================================================
-- V020 — Corrections commerciales : retours clients, avoirs, corrections de caisse
--
-- customer_return : retour (partiel ou total) d'une vente enregistrée. C'est aussi
-- l'AVOIR du client (numéro RET-AAAA-nnnnnn, montant). Règlement :
--   * REFUND         : remboursement depuis la caisse (écriture RETURN_REFUND) ;
--   * ACCOUNT_CREDIT : avoir imputé sur la dette du client (écriture CREDIT_NOTE du compte).
-- customer_return_line : quantité retournée par ligne de vente, montant au prix net de la
-- vente, état de l'article ; un article revendable revient en stock (mouvement
-- CUSTOMER_RETURN au coût de la vente), un article défectueux non.
-- Les deux tables sont en ajout seul : un retour enregistré ne se modifie ni ne se supprime.
--
-- Caisse : nouveaux types d'écriture RETURN_REFUND (sortie) et CORRECTION_IN / CORRECTION_OUT
-- (écriture inverse d'un apport ou d'une sortie manuelle saisi par erreur, désigné par
-- reversal_of_id, une seule fois). Rien n'est jamais effacé.
--
-- Garde-fous :
--   * fn_customer_return_line_guard : ligne de la vente retournée, même produit, et mouvement
--     CUSTOMER_RETURN exact pour un article revendable ;
--   * fn_customer_return_consistency (DIFFÉRÉE) : retour non vide, total = lignes, jamais plus
--     que la quantité vendue, vente non annulée, règlement présent (remboursement en caisse
--     ou avoir au compte du même montant) ;
--   * fn_sale_return_guard (DIFFÉRÉE) : une vente ayant des retours ne peut plus être annulée.
--
-- Annulation : db/rollback/U020__customer_return.sql
-- =============================================================================

-- --- Caisse : remboursement de retour et corrections ---------------------------------------

ALTER TABLE cash_entry ADD COLUMN reversal_of_id uuid;
ALTER TABLE cash_entry
    ADD CONSTRAINT fk_cash_entry_reversal_of FOREIGN KEY (reversal_of_id) REFERENCES cash_entry (id) ON DELETE RESTRICT;
CREATE UNIQUE INDEX uk_cash_entry_reversal_of_id ON cash_entry (reversal_of_id) WHERE reversal_of_id IS NOT NULL;

ALTER TABLE cash_entry DROP CONSTRAINT ck_cash_entry_type;
ALTER TABLE cash_entry DROP CONSTRAINT ck_cash_entry_direction;
ALTER TABLE cash_entry DROP CONSTRAINT ck_cash_entry_manual_cash;
ALTER TABLE cash_entry DROP CONSTRAINT ck_cash_entry_source;

ALTER TABLE cash_entry ADD CONSTRAINT ck_cash_entry_type CHECK (entry_type IN (
    'SALE', 'CUSTOMER_PAYMENT', 'CASH_IN', 'CORRECTION_IN', 'SALE_REFUND', 'RETURN_REFUND', 'CASH_OUT', 'CORRECTION_OUT'));
ALTER TABLE cash_entry ADD CONSTRAINT ck_cash_entry_direction CHECK (
    (entry_type IN ('SALE', 'CUSTOMER_PAYMENT', 'CASH_IN', 'CORRECTION_IN') AND direction = 'IN')
    OR (entry_type IN ('SALE_REFUND', 'RETURN_REFUND', 'CASH_OUT', 'CORRECTION_OUT') AND direction = 'OUT'));
ALTER TABLE cash_entry ADD CONSTRAINT ck_cash_entry_manual_cash CHECK (
    entry_type NOT IN ('CASH_IN', 'CASH_OUT', 'CORRECTION_IN', 'CORRECTION_OUT') OR payment_method = 'CASH');
ALTER TABLE cash_entry ADD CONSTRAINT ck_cash_entry_source CHECK (
    entry_type NOT IN ('SALE', 'SALE_REFUND', 'RETURN_REFUND', 'CUSTOMER_PAYMENT')
    OR (source_type IS NOT NULL AND source_id IS NOT NULL));
-- Une correction désigne l'écriture qu'elle corrige, et seule une correction le fait.
ALTER TABLE cash_entry ADD CONSTRAINT ck_cash_entry_correction CHECK (
    (entry_type IN ('CORRECTION_IN', 'CORRECTION_OUT')) = (reversal_of_id IS NOT NULL));

COMMENT ON COLUMN cash_entry.reversal_of_id IS 'Écriture manuelle corrigée par cette correction (une seule fois).';

-- Une correction : même caisse, montant identique, sens inverse d'un apport ou d'une sortie manuelle.
CREATE FUNCTION fn_cash_correction_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    o cash_entry%ROWTYPE;
BEGIN
    IF NEW.reversal_of_id IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT * INTO o FROM cash_entry WHERE id = NEW.reversal_of_id;
    IF o.id IS NULL OR o.session_id <> NEW.session_id OR o.amount <> NEW.amount
       OR NOT ((o.entry_type = 'CASH_IN' AND NEW.entry_type = 'CORRECTION_OUT')
            OR (o.entry_type = 'CASH_OUT' AND NEW.entry_type = 'CORRECTION_IN')) THEN
        RAISE EXCEPTION 'cash_entry % : une correction annule un apport ou une sortie manuelle de la même caisse, '
            'pour le même montant.', NEW.number
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION fn_cash_correction_guard() IS 'Trigger de cash_entry : correction = inverse exact d''une opération manuelle.';

CREATE TRIGGER trg_cash_entry_correction_guard
    BEFORE INSERT ON cash_entry
    FOR EACH ROW EXECUTE FUNCTION fn_cash_correction_guard();

-- --- Retours clients ------------------------------------------------------------------------

CREATE SEQUENCE seq_customer_return_number;

CREATE TABLE customer_return (
    id             uuid          NOT NULL DEFAULT gen_random_uuid(),
    number         varchar(30)   NOT NULL,
    sale_id        uuid          NOT NULL,
    warehouse_id   uuid          NOT NULL,
    customer_id    uuid,
    settlement     varchar(20)   NOT NULL,
    refund_method  varchar(20),
    total_amount   numeric(14,2) NOT NULL,
    reason         varchar(500)  NOT NULL,
    created_at     timestamptz   NOT NULL,
    created_by     uuid          NOT NULL,
    CONSTRAINT pk_customer_return PRIMARY KEY (id),
    CONSTRAINT fk_customer_return_sale FOREIGN KEY (sale_id) REFERENCES sale (id) ON DELETE RESTRICT,
    CONSTRAINT fk_customer_return_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse (id) ON DELETE RESTRICT,
    CONSTRAINT fk_customer_return_customer FOREIGN KEY (customer_id) REFERENCES customer (id) ON DELETE RESTRICT,
    CONSTRAINT fk_customer_return_created_by FOREIGN KEY (created_by) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_customer_return_settlement CHECK (settlement IN ('REFUND', 'ACCOUNT_CREDIT')),
    CONSTRAINT ck_customer_return_refund_method CHECK (
        (settlement = 'REFUND' AND refund_method IN ('CASH', 'MOBILE_MONEY', 'BANK_TRANSFER'))
        OR (settlement = 'ACCOUNT_CREDIT' AND refund_method IS NULL AND customer_id IS NOT NULL)),
    CONSTRAINT ck_customer_return_total CHECK (total_amount > 0),
    CONSTRAINT ck_customer_return_reason CHECK (btrim(reason) <> '')
);

CREATE UNIQUE INDEX uk_customer_return_number ON customer_return (number);
CREATE INDEX ix_customer_return_sale_id ON customer_return (sale_id);
CREATE INDEX ix_customer_return_warehouse_id_created_at ON customer_return (warehouse_id, created_at DESC);
CREATE INDEX ix_customer_return_customer_id ON customer_return (customer_id);
CREATE INDEX ix_customer_return_created_by ON customer_return (created_by);

COMMENT ON TABLE customer_return IS 'Retours clients (avoirs), en ajout seul : remboursés en caisse ou imputés sur la dette.';
COMMENT ON COLUMN customer_return.number IS 'Numéro de l''avoir RET-AAAA-nnnnnn (séquence seq_customer_return_number).';

CREATE TABLE customer_return_line (
    id                uuid          NOT NULL DEFAULT gen_random_uuid(),
    return_id         uuid          NOT NULL,
    line_number       integer       NOT NULL,
    sale_line_id      uuid          NOT NULL,
    product_id        uuid          NOT NULL,
    product_sku       varchar(50)   NOT NULL,
    product_name      varchar(200)  NOT NULL,
    quantity          integer       NOT NULL,
    amount            numeric(14,2) NOT NULL,
    unit_cost         numeric(14,2) NOT NULL,
    item_condition    varchar(20)   NOT NULL,
    stock_movement_id uuid,
    CONSTRAINT pk_customer_return_line PRIMARY KEY (id),
    CONSTRAINT fk_customer_return_line_return FOREIGN KEY (return_id) REFERENCES customer_return (id) ON DELETE RESTRICT,
    CONSTRAINT fk_customer_return_line_sale_line FOREIGN KEY (sale_line_id) REFERENCES sale_line (id) ON DELETE RESTRICT,
    CONSTRAINT fk_customer_return_line_product FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE RESTRICT,
    CONSTRAINT fk_customer_return_line_stock_movement FOREIGN KEY (stock_movement_id)
        REFERENCES stock_movement (id) ON DELETE RESTRICT,
    CONSTRAINT ck_customer_return_line_line_number CHECK (line_number > 0),
    CONSTRAINT ck_customer_return_line_quantity CHECK (quantity > 0),
    CONSTRAINT ck_customer_return_line_amount CHECK (amount >= 0 AND unit_cost >= 0),
    CONSTRAINT ck_customer_return_line_condition CHECK (item_condition IN ('RESELLABLE', 'DEFECTIVE')),
    -- Revendable ⇔ remis en stock.
    CONSTRAINT ck_customer_return_line_restock CHECK ((item_condition = 'RESELLABLE') = (stock_movement_id IS NOT NULL))
);

CREATE UNIQUE INDEX uk_customer_return_line_number ON customer_return_line (return_id, line_number);
CREATE UNIQUE INDEX uk_customer_return_line_stock_movement_id ON customer_return_line (stock_movement_id)
    WHERE stock_movement_id IS NOT NULL;
CREATE INDEX ix_customer_return_line_sale_line_id ON customer_return_line (sale_line_id);
CREATE INDEX ix_customer_return_line_product_id ON customer_return_line (product_id);

COMMENT ON TABLE customer_return_line IS 'Articles retournés : quantité, montant au prix net de la vente, état, remise en stock.';

CREATE TRIGGER trg_customer_return_prevent_modification
    BEFORE UPDATE OR DELETE ON customer_return
    FOR EACH ROW EXECUTE FUNCTION fn_prevent_modification();
CREATE TRIGGER trg_customer_return_prevent_truncate
    BEFORE TRUNCATE ON customer_return
    FOR EACH STATEMENT EXECUTE FUNCTION fn_prevent_modification();
CREATE TRIGGER trg_customer_return_line_prevent_modification
    BEFORE UPDATE OR DELETE ON customer_return_line
    FOR EACH ROW EXECUTE FUNCTION fn_prevent_modification();
CREATE TRIGGER trg_customer_return_line_prevent_truncate
    BEFORE TRUNCATE ON customer_return_line
    FOR EACH STATEMENT EXECUTE FUNCTION fn_prevent_modification();

-- Une ligne retournée : ligne de la vente retournée ; revendable ⇒ entrée en stock exacte.
CREATE FUNCTION fn_customer_return_line_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    r  customer_return%ROWTYPE;
    sl sale_line%ROWTYPE;
    m  stock_movement%ROWTYPE;
BEGIN
    SELECT * INTO r FROM customer_return WHERE id = NEW.return_id;
    SELECT * INTO sl FROM sale_line WHERE id = NEW.sale_line_id;
    IF r.id IS NULL OR sl.id IS NULL OR sl.sale_id <> r.sale_id OR sl.product_id <> NEW.product_id
       OR sl.unit_cost <> NEW.unit_cost THEN
        RAISE EXCEPTION 'customer_return_line : la ligne ne correspond pas à la vente retournée.'
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.stock_movement_id IS NOT NULL THEN
        SELECT * INTO m FROM stock_movement WHERE id = NEW.stock_movement_id;
        IF m.id IS NULL OR m.type <> 'CUSTOMER_RETURN' OR m.source_type <> 'CUSTOMER_RETURN'
           OR m.source_id IS DISTINCT FROM NEW.return_id OR m.warehouse_id <> r.warehouse_id
           OR m.product_id <> NEW.product_id OR m.quantity <> NEW.quantity
           OR m.unit_cost IS DISTINCT FROM NEW.unit_cost THEN
            RAISE EXCEPTION 'customer_return_line : aucune remise en stock ne correspond à cet article (mouvement %).',
                NEW.stock_movement_id
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION fn_customer_return_line_guard() IS
    'Trigger de customer_return_line : ligne de la vente retournée, remise en stock exacte si revendable.';

CREATE TRIGGER trg_customer_return_line_guard
    BEFORE INSERT ON customer_return_line
    FOR EACH ROW EXECUTE FUNCTION fn_customer_return_line_guard();

-- Cohérence d'un retour, vérifiée au COMMIT.
CREATE FUNCTION fn_customer_return_consistency() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    r       customer_return%ROWTYPE;
    lines   integer;
    amount  numeric;
    over    integer;
    st      varchar(20);
BEGIN
    SELECT * INTO r FROM customer_return WHERE id = NEW.id;
    SELECT count(*), coalesce(sum(l.amount), 0) INTO lines, amount FROM customer_return_line l WHERE l.return_id = r.id;
    IF lines = 0 OR amount <> r.total_amount THEN
        RAISE EXCEPTION 'customer_return % : retour sans ligne ou total différent de la somme des lignes.', r.number
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT count(*) INTO over
      FROM sale_line sl
     WHERE sl.sale_id = r.sale_id
       AND sl.quantity < (SELECT coalesce(sum(l.quantity), 0) FROM customer_return_line l WHERE l.sale_line_id = sl.id);
    IF over > 0 THEN
        RAISE EXCEPTION 'customer_return % : quantité retournée supérieure à la quantité vendue.', r.number
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT status INTO st FROM sale WHERE id = r.sale_id;
    IF st <> 'COMPLETED' THEN
        RAISE EXCEPTION 'customer_return % : retour sur une vente annulée.', r.number
            USING ERRCODE = 'check_violation';
    END IF;

    IF r.settlement = 'REFUND' AND NOT EXISTS (
            SELECT 1 FROM cash_entry e
            WHERE e.entry_type = 'RETURN_REFUND' AND e.source_type = 'CUSTOMER_RETURN' AND e.source_id = r.id
              AND e.amount = r.total_amount AND e.payment_method = r.refund_method) THEN
        RAISE EXCEPTION 'customer_return % : remboursement absent de la caisse.', r.number
            USING ERRCODE = 'check_violation';
    END IF;
    IF r.settlement = 'ACCOUNT_CREDIT' AND NOT EXISTS (
            SELECT 1 FROM customer_account_entry a
            WHERE a.entry_type = 'CREDIT_NOTE' AND a.source_type = 'CUSTOMER_RETURN' AND a.source_id = r.id
              AND a.customer_id = r.customer_id AND a.amount = r.total_amount) THEN
        RAISE EXCEPTION 'customer_return % : avoir absent du compte du client.', r.number
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

COMMENT ON FUNCTION fn_customer_return_consistency() IS
    'Contrainte différée : retour non vide, total = lignes, pas plus que vendu, vente non annulée, règlement présent.';

CREATE CONSTRAINT TRIGGER trg_customer_return_consistency
    AFTER INSERT ON customer_return
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION fn_customer_return_consistency();

-- Une vente ayant des retours ne peut plus être annulée (la marchandise serait restituée deux fois).
CREATE FUNCTION fn_sale_return_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'CANCELLED' AND EXISTS (SELECT 1 FROM customer_return r WHERE r.sale_id = NEW.id) THEN
        RAISE EXCEPTION 'sale % : vente ayant des retours, elle ne peut plus être annulée.', NEW.number
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

COMMENT ON FUNCTION fn_sale_return_guard() IS 'Contrainte différée : pas d''annulation d''une vente ayant des retours.';

CREATE CONSTRAINT TRIGGER trg_sale_return_guard
    AFTER UPDATE OF status ON sale
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION fn_sale_return_guard();
