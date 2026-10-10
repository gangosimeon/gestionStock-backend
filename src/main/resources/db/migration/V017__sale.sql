-- =============================================================================
-- V017 — Ventes
--
-- Contrat front : Sale { id, type: RETAIL|WHOLESALE, customerId?, items: [{productId,
-- quantity, unitPrice, purchasePrice}], paymentMethod, paidAmount, total, profit, createdAt,
-- createdByUserId }. Prix, coûts et montants sont CALCULÉS PAR LE SERVEUR : les prix envoyés
-- par le client sont ignorés (l'audit relevait une marge falsifiable).
--
-- sale : en-tête (numéro VTE-AAAA-nnnnnn, dépôt, client, tarif, montants, règlement,
-- statut). Montants figés à l'enregistrement ; seule l'annulation modifie la vente.
-- sale_line : lignes, en ajout seul ; chacune désigne LE mouvement de stock SALE qu'elle a
-- produit (même dépôt, produit, quantité, coût).
--
-- Garde-fous en base :
--   * fn_sale_guard : montants, client, dépôt et numéro immuables ; seul passage autorisé
--     COMPLETED → CANCELLED ; jamais de suppression ;
--   * fn_sale_line_guard (à l'insertion) : une ligne vendue = une sortie de stock exacte ;
--   * fn_sale_consistency (contrainte DIFFÉRÉE, au COMMIT) : totaux de l'en-tête = somme des
--     lignes ; vente à crédit ⇒ créance CREDIT_SALE du même montant au compte du client ;
--     vente annulée ⇒ chaque ligne remise en stock (SALE_CANCELLATION) et créance annulée.
--   * ck_sale_credit_customer : un reste dû exige un client (RG-SAL-3, imposé en base).
--
-- Annulation : db/rollback/U017__sale.sql
-- =============================================================================

CREATE SEQUENCE seq_sale_number;

CREATE TABLE sale (
    id                  uuid          NOT NULL DEFAULT gen_random_uuid(),
    number              varchar(30)   NOT NULL,
    warehouse_id        uuid          NOT NULL,
    customer_id         uuid,
    customer_name       varchar(200),
    price_type          varchar(20)   NOT NULL,
    status              varchar(20)   NOT NULL DEFAULT 'COMPLETED',
    subtotal            numeric(14,2) NOT NULL,
    discount_total      numeric(14,2) NOT NULL,
    total               numeric(14,2) NOT NULL,
    cost_total          numeric(14,2) NOT NULL,
    profit              numeric(14,2) NOT NULL,
    paid_amount         numeric(14,2) NOT NULL,
    credit_amount       numeric(14,2) NOT NULL,
    payment_method      varchar(20),
    note                varchar(500),
    cancelled_at        timestamptz,
    cancelled_by        uuid,
    cancellation_reason varchar(500),
    version             bigint        NOT NULL DEFAULT 0,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    created_by          uuid,
    updated_at          timestamptz   NOT NULL DEFAULT now(),
    updated_by          uuid,
    CONSTRAINT pk_sale PRIMARY KEY (id),
    CONSTRAINT fk_sale_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sale_customer FOREIGN KEY (customer_id) REFERENCES customer (id) ON DELETE RESTRICT,
    CONSTRAINT ck_sale_price_type CHECK (price_type IN ('RETAIL', 'WHOLESALE')),
    CONSTRAINT ck_sale_status CHECK (status IN ('COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_sale_payment_method CHECK (payment_method IS NULL OR payment_method IN ('CASH', 'MOBILE_MONEY', 'BANK_TRANSFER')),
    CONSTRAINT ck_sale_amounts CHECK (subtotal >= 0 AND discount_total >= 0 AND cost_total >= 0
                                      AND total = subtotal - discount_total AND total >= 0
                                      AND profit = total - cost_total),
    CONSTRAINT ck_sale_payment CHECK (paid_amount >= 0 AND paid_amount <= total AND credit_amount = total - paid_amount),
    CONSTRAINT ck_sale_payment_method_required CHECK (paid_amount = 0 OR payment_method IS NOT NULL),
    -- RG-SAL-3 : pas de crédit sans client identifié.
    CONSTRAINT ck_sale_credit_customer CHECK (credit_amount = 0 OR customer_id IS NOT NULL),
    CONSTRAINT ck_sale_cancellation CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL))
);

CREATE UNIQUE INDEX uk_sale_number ON sale (number);
CREATE INDEX ix_sale_warehouse_id_created_at ON sale (warehouse_id, created_at DESC);
CREATE INDEX ix_sale_customer_id ON sale (customer_id, created_at DESC);
CREATE INDEX ix_sale_created_at ON sale (created_at DESC);

COMMENT ON TABLE sale IS 'Ventes. Montants calculés par le serveur et figés ; jamais supprimées, annulées par statut.';
COMMENT ON COLUMN sale.number IS 'Numéro lisible VTE-AAAA-nnnnnn (séquence seq_sale_number).';
COMMENT ON COLUMN sale.price_type IS 'Tarif appliqué : RETAIL (détail) ou WHOLESALE (gros).';
COMMENT ON COLUMN sale.cost_total IS 'Coût des marchandises vendues (prix d''achat de référence au moment de la vente).';
COMMENT ON COLUMN sale.credit_amount IS 'Reste dû = total − payé, porté au compte du client.';
COMMENT ON COLUMN sale.customer_name IS 'Nom du client à la date de la vente (instantané).';

CREATE TABLE sale_line (
    id                uuid          NOT NULL DEFAULT gen_random_uuid(),
    sale_id           uuid          NOT NULL,
    line_number       integer       NOT NULL,
    product_id        uuid          NOT NULL,
    product_sku       varchar(50)   NOT NULL,
    product_name      varchar(200)  NOT NULL,
    quantity          integer       NOT NULL,
    unit_price        numeric(14,2) NOT NULL,
    discount_percent  numeric(5,2)  NOT NULL DEFAULT 0,
    discount_amount   numeric(14,2) NOT NULL DEFAULT 0,
    line_total        numeric(14,2) NOT NULL,
    unit_cost         numeric(14,2) NOT NULL,
    cost_total        numeric(14,2) NOT NULL,
    stock_movement_id uuid          NOT NULL,
    CONSTRAINT pk_sale_line PRIMARY KEY (id),
    CONSTRAINT fk_sale_line_sale FOREIGN KEY (sale_id) REFERENCES sale (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sale_line_product FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sale_line_stock_movement FOREIGN KEY (stock_movement_id) REFERENCES stock_movement (id) ON DELETE RESTRICT,
    CONSTRAINT ck_sale_line_line_number CHECK (line_number > 0),
    CONSTRAINT ck_sale_line_quantity CHECK (quantity > 0),
    CONSTRAINT ck_sale_line_unit_price CHECK (unit_price > 0),
    CONSTRAINT ck_sale_line_discount CHECK (discount_percent BETWEEN 0 AND 100 AND discount_amount >= 0
                                            AND discount_amount <= quantity * unit_price),
    CONSTRAINT ck_sale_line_total CHECK (line_total = quantity * unit_price - discount_amount),
    CONSTRAINT ck_sale_line_cost CHECK (unit_cost >= 0 AND cost_total = quantity * unit_cost)
);

CREATE UNIQUE INDEX uk_sale_line_number ON sale_line (sale_id, line_number);
CREATE UNIQUE INDEX uk_sale_line_product ON sale_line (sale_id, product_id);
CREATE UNIQUE INDEX uk_sale_line_stock_movement_id ON sale_line (stock_movement_id);
CREATE INDEX ix_sale_line_product_id ON sale_line (product_id);

COMMENT ON TABLE sale_line IS 'Lignes de vente, en ajout seul ; chacune désigne la sortie de stock SALE qu''elle a produite.';
COMMENT ON COLUMN sale_line.unit_price IS 'Prix unitaire du tarif appliqué, relu par le serveur (jamais celui du client).';
COMMENT ON COLUMN sale_line.unit_cost IS 'Coût unitaire (prix d''achat de référence) au moment de la vente.';

-- --- Immuabilité ---------------------------------------------------------------------------

CREATE TRIGGER trg_sale_line_prevent_modification
    BEFORE UPDATE OR DELETE ON sale_line
    FOR EACH ROW EXECUTE FUNCTION fn_prevent_modification();
CREATE TRIGGER trg_sale_line_prevent_truncate
    BEFORE TRUNCATE ON sale_line
    FOR EACH STATEMENT EXECUTE FUNCTION fn_prevent_modification();
CREATE TRIGGER trg_sale_prevent_delete
    BEFORE DELETE ON sale
    FOR EACH ROW EXECUTE FUNCTION fn_prevent_modification();
CREATE TRIGGER trg_sale_prevent_truncate
    BEFORE TRUNCATE ON sale
    FOR EACH STATEMENT EXECUTE FUNCTION fn_prevent_modification();

CREATE FUNCTION fn_sale_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.number <> OLD.number
       OR NEW.warehouse_id <> OLD.warehouse_id
       OR NEW.customer_id IS DISTINCT FROM OLD.customer_id
       OR NEW.price_type <> OLD.price_type
       OR NEW.subtotal <> OLD.subtotal
       OR NEW.discount_total <> OLD.discount_total
       OR NEW.total <> OLD.total
       OR NEW.cost_total <> OLD.cost_total
       OR NEW.profit <> OLD.profit
       OR NEW.paid_amount <> OLD.paid_amount
       OR NEW.credit_amount <> OLD.credit_amount
       OR NEW.payment_method IS DISTINCT FROM OLD.payment_method
       OR NEW.created_at <> OLD.created_at
       OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
        RAISE EXCEPTION 'sale % : une vente enregistrée ne se modifie pas (seule l''annulation est permise).', OLD.number
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.status <> OLD.status AND NOT (OLD.status = 'COMPLETED' AND NEW.status = 'CANCELLED') THEN
        RAISE EXCEPTION 'sale % : passage de statut % → % interdit.', OLD.number, OLD.status, NEW.status
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION fn_sale_guard() IS 'Trigger de sale : montants et identité figés, seule l''annulation est permise.';

CREATE TRIGGER trg_sale_guard
    BEFORE UPDATE ON sale
    FOR EACH ROW EXECUTE FUNCTION fn_sale_guard();

-- --- Une ligne vendue = une sortie de stock exacte -----------------------------------------

CREATE FUNCTION fn_sale_line_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    s sale%ROWTYPE;
    m stock_movement%ROWTYPE;
BEGIN
    SELECT * INTO s FROM sale WHERE id = NEW.sale_id;
    SELECT * INTO m FROM stock_movement WHERE id = NEW.stock_movement_id;
    IF s.id IS NULL OR m.id IS NULL
       OR m.type <> 'SALE'
       OR m.source_type <> 'SALE'
       OR m.source_id IS DISTINCT FROM NEW.sale_id
       OR m.warehouse_id <> s.warehouse_id
       OR m.product_id <> NEW.product_id
       OR m.quantity <> -NEW.quantity
       OR m.unit_cost IS DISTINCT FROM NEW.unit_cost THEN
        RAISE EXCEPTION 'sale_line : aucune sortie de stock ne correspond à cette ligne vendue (mouvement %).',
            NEW.stock_movement_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION fn_sale_line_guard() IS
    'Trigger de sale_line : une ligne vendue désigne la sortie de stock SALE exacte qu''elle a produite.';

CREATE TRIGGER trg_sale_line_guard
    BEFORE INSERT ON sale_line
    FOR EACH ROW EXECUTE FUNCTION fn_sale_line_guard();

-- --- Cohérence vérifiée au COMMIT ------------------------------------------------------------

CREATE FUNCTION fn_sale_consistency() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    s       sale%ROWTYPE;
    lines   integer;
    gross   numeric;
    disc    numeric;
    net     numeric;
    cost    numeric;
    missing integer;
BEGIN
    SELECT * INTO s FROM sale WHERE id = NEW.id;
    SELECT count(*), coalesce(sum(quantity * unit_price), 0), coalesce(sum(discount_amount), 0),
           coalesce(sum(line_total), 0), coalesce(sum(cost_total), 0)
      INTO lines, gross, disc, net, cost
      FROM sale_line WHERE sale_id = s.id;
    IF lines = 0 OR gross <> s.subtotal OR disc <> s.discount_total OR net <> s.total OR cost <> s.cost_total THEN
        RAISE EXCEPTION 'sale % : vente sans ligne ou totaux différents de la somme des lignes.', s.number
            USING ERRCODE = 'check_violation';
    END IF;

    IF s.credit_amount > 0 AND NOT EXISTS (
            SELECT 1 FROM customer_account_entry e
            WHERE e.source_type = 'SALE' AND e.source_id = s.id AND e.entry_type = 'CREDIT_SALE'
              AND e.customer_id = s.customer_id AND e.amount = s.credit_amount) THEN
        RAISE EXCEPTION 'sale % : vente à crédit sans créance au compte du client.', s.number
            USING ERRCODE = 'check_violation';
    END IF;

    IF s.status = 'CANCELLED' THEN
        SELECT count(*) INTO missing
          FROM sale_line l
         WHERE l.sale_id = s.id
           AND l.quantity <> coalesce((
                   SELECT sum(m.quantity) FROM stock_movement m
                   WHERE m.type = 'SALE_CANCELLATION' AND m.source_type = 'SALE' AND m.source_id = s.id
                     AND m.product_id = l.product_id AND m.warehouse_id = s.warehouse_id), 0);
        IF missing > 0 THEN
            RAISE EXCEPTION 'sale % : vente annulée sans remise en stock de toutes ses lignes.', s.number
                USING ERRCODE = 'check_violation';
        END IF;
        IF s.credit_amount > 0 AND NOT EXISTS (
                SELECT 1 FROM customer_account_entry e
                WHERE e.source_type = 'SALE' AND e.source_id = s.id AND e.entry_type = 'SALE_CANCELLATION'
                  AND e.customer_id = s.customer_id AND e.amount = s.credit_amount) THEN
            RAISE EXCEPTION 'sale % : vente à crédit annulée sans annulation de la créance.', s.number
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

COMMENT ON FUNCTION fn_sale_consistency() IS
    'Contrainte différée : totaux = lignes ; crédit ⇒ créance ; annulation ⇒ remise en stock et créance annulée.';

CREATE CONSTRAINT TRIGGER trg_sale_consistency
    AFTER INSERT OR UPDATE OF status ON sale
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION fn_sale_consistency();
