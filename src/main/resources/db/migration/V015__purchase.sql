-- =============================================================================
-- V015 — Achats : commandes fournisseurs et réceptions
--
-- Workflow : COMMANDE (ORDERED) → RÉCEPTION(S) (PARTIALLY_RECEIVED → RECEIVED) → STOCK.
--            Sorties : CANCELLED (rien reçu) ou CLOSED (reliquat abandonné).
--
-- purchase_order / purchase_order_line : la commande, ses quantités commandées et reçues,
-- son coût unitaire négocié et ses montants calculés par le serveur. Fournisseur, référence
-- et nom du produit sont recopiés (une pièce garde les libellés de son émission).
-- Les lignes n'évoluent qu'à travers la commande (verrouillée et versionnée) : elles ne
-- portent ni version ni colonnes d'audit propres.
--
-- goods_receipt / goods_receipt_line : journal des réceptions, en ajout seul. Chaque ligne
-- reçue désigne LE mouvement de stock RECEIPT qu'elle a produit.
--
-- Garde-fous en base, pour que « réception validée sans stock » soit impossible quelle que
-- soit la source de l'écriture :
--   * fn_goods_receipt_line_guard (à l'insertion) : la ligne doit désigner un mouvement
--     RECEIPT de la même réception (source_id), du même dépôt, du même produit, de la même
--     quantité et du même coût, et une ligne de la même commande ;
--   * fn_purchase_receipt_consistency (contrainte DIFFÉRÉE, vérifiée au COMMIT) : la quantité
--     reçue d'une ligne de commande est toujours égale à la somme de ses lignes de réception,
--     et une réception a au moins une ligne dont le total égale son en-tête.
-- Le mouvement de stock, lui, est protégé par fn_stock_level_guard (V013).
--
-- Annulation : db/rollback/U015__purchase.sql
-- =============================================================================

CREATE SEQUENCE seq_purchase_order_number;
CREATE SEQUENCE seq_goods_receipt_number;

CREATE TABLE purchase_order (
    id                     uuid          NOT NULL DEFAULT gen_random_uuid(),
    number                 varchar(30)   NOT NULL,
    supplier_id            uuid          NOT NULL,
    supplier_name          varchar(200)  NOT NULL,
    warehouse_id           uuid          NOT NULL,
    status                 varchar(20)   NOT NULL DEFAULT 'ORDERED',
    expected_delivery_date date,
    supplier_reference     varchar(50),
    note                   varchar(1000),
    total_amount           numeric(14,2) NOT NULL,
    received_amount        numeric(14,2) NOT NULL DEFAULT 0,
    last_receipt_at        timestamptz,
    closed_at              timestamptz,
    closed_by              uuid,
    close_reason           varchar(500),
    version                bigint        NOT NULL DEFAULT 0,
    created_at             timestamptz   NOT NULL DEFAULT now(),
    created_by             uuid,
    updated_at             timestamptz   NOT NULL DEFAULT now(),
    updated_by             uuid,
    CONSTRAINT pk_purchase_order PRIMARY KEY (id),
    CONSTRAINT fk_purchase_order_supplier FOREIGN KEY (supplier_id) REFERENCES supplier (id) ON DELETE RESTRICT,
    CONSTRAINT fk_purchase_order_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse (id) ON DELETE RESTRICT,
    CONSTRAINT ck_purchase_order_status CHECK (status IN ('ORDERED', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CLOSED', 'CANCELLED')),
    CONSTRAINT ck_purchase_order_amounts CHECK (total_amount >= 0 AND received_amount >= 0 AND received_amount <= total_amount),
    -- Une commande close (soldée ou annulée) porte sa date de clôture, et elle seule.
    CONSTRAINT ck_purchase_order_closing CHECK ((status IN ('CLOSED', 'CANCELLED')) = (closed_at IS NOT NULL)),
    -- Reçue (même en partie) ⇔ au moins une réception ; une commande annulée n'a rien reçu.
    CONSTRAINT ck_purchase_order_receipt CHECK ((status IN ('PARTIALLY_RECEIVED', 'RECEIVED', 'CLOSED')) = (last_receipt_at IS NOT NULL))
);

CREATE UNIQUE INDEX uk_purchase_order_number ON purchase_order (number);
CREATE INDEX ix_purchase_order_supplier_id ON purchase_order (supplier_id, created_at DESC);
CREATE INDEX ix_purchase_order_warehouse_id_created_at ON purchase_order (warehouse_id, created_at DESC);
CREATE INDEX ix_purchase_order_status ON purchase_order (status);

COMMENT ON TABLE purchase_order IS 'Commandes fournisseurs. Jamais supprimées : cycle de vie par statut.';
COMMENT ON COLUMN purchase_order.number IS 'Numéro lisible CMD-AAAA-nnnnnn (séquence seq_purchase_order_number).';
COMMENT ON COLUMN purchase_order.warehouse_id IS 'Dépôt de livraison : les réceptions y entrent en stock.';
COMMENT ON COLUMN purchase_order.total_amount IS 'Somme des lignes (quantité commandée × coût unitaire), calculée par le serveur.';
COMMENT ON COLUMN purchase_order.received_amount IS 'Valeur reçue à ce jour (somme des réceptions).';

CREATE TABLE purchase_order_line (
    id                uuid          NOT NULL DEFAULT gen_random_uuid(),
    purchase_order_id uuid          NOT NULL,
    line_number       integer       NOT NULL,
    product_id        uuid          NOT NULL,
    product_sku       varchar(50)   NOT NULL,
    product_name      varchar(200)  NOT NULL,
    ordered_quantity  integer       NOT NULL,
    received_quantity integer       NOT NULL DEFAULT 0,
    unit_cost         numeric(14,2) NOT NULL,
    line_total        numeric(14,2) NOT NULL,
    CONSTRAINT pk_purchase_order_line PRIMARY KEY (id),
    CONSTRAINT fk_purchase_order_line_purchase_order FOREIGN KEY (purchase_order_id)
        REFERENCES purchase_order (id) ON DELETE RESTRICT,
    CONSTRAINT fk_purchase_order_line_product FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE RESTRICT,
    CONSTRAINT ck_purchase_order_line_line_number CHECK (line_number > 0),
    CONSTRAINT ck_purchase_order_line_ordered_quantity CHECK (ordered_quantity > 0),
    CONSTRAINT ck_purchase_order_line_received_quantity CHECK (received_quantity BETWEEN 0 AND ordered_quantity),
    CONSTRAINT ck_purchase_order_line_unit_cost CHECK (unit_cost >= 0),
    CONSTRAINT ck_purchase_order_line_total CHECK (line_total = ordered_quantity * unit_cost)
);

CREATE UNIQUE INDEX uk_purchase_order_line_number ON purchase_order_line (purchase_order_id, line_number);
CREATE UNIQUE INDEX uk_purchase_order_line_product ON purchase_order_line (purchase_order_id, product_id);
CREATE INDEX ix_purchase_order_line_product_id ON purchase_order_line (product_id);

COMMENT ON TABLE purchase_order_line IS 'Lignes de commande fournisseur : quantités commandée et reçue, coût unitaire.';
COMMENT ON COLUMN purchase_order_line.received_quantity IS 'Toujours égale à la somme des lignes de réception (contrainte différée).';

CREATE TABLE goods_receipt (
    id                     uuid          NOT NULL DEFAULT gen_random_uuid(),
    number                 varchar(30)   NOT NULL,
    purchase_order_id      uuid          NOT NULL,
    warehouse_id           uuid          NOT NULL,
    received_at            timestamptz   NOT NULL,
    supplier_delivery_note varchar(50),
    note                   varchar(1000),
    total_quantity         integer       NOT NULL,
    total_amount           numeric(14,2) NOT NULL,
    created_at             timestamptz   NOT NULL DEFAULT now(),
    created_by             uuid          NOT NULL,
    CONSTRAINT pk_goods_receipt PRIMARY KEY (id),
    CONSTRAINT fk_goods_receipt_purchase_order FOREIGN KEY (purchase_order_id)
        REFERENCES purchase_order (id) ON DELETE RESTRICT,
    CONSTRAINT fk_goods_receipt_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse (id) ON DELETE RESTRICT,
    CONSTRAINT fk_goods_receipt_created_by FOREIGN KEY (created_by) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_goods_receipt_total_quantity CHECK (total_quantity > 0),
    CONSTRAINT ck_goods_receipt_total_amount CHECK (total_amount >= 0)
);

CREATE UNIQUE INDEX uk_goods_receipt_number ON goods_receipt (number);
CREATE INDEX ix_goods_receipt_purchase_order_id ON goods_receipt (purchase_order_id, received_at);
CREATE INDEX ix_goods_receipt_warehouse_id_received_at ON goods_receipt (warehouse_id, received_at DESC);
CREATE INDEX ix_goods_receipt_created_by ON goods_receipt (created_by);

COMMENT ON TABLE goods_receipt IS 'Bons de réception fournisseur, en ajout seul. Chaque réception entre en stock dans la même transaction.';
COMMENT ON COLUMN goods_receipt.received_at IS 'Date de réception déclarée (au plus tard la validation).';
COMMENT ON COLUMN goods_receipt.created_by IS 'Utilisateur ayant validé la réception.';

CREATE TABLE goods_receipt_line (
    id                     uuid          NOT NULL DEFAULT gen_random_uuid(),
    goods_receipt_id       uuid          NOT NULL,
    line_number            integer       NOT NULL,
    purchase_order_line_id uuid          NOT NULL,
    product_id             uuid          NOT NULL,
    quantity               integer       NOT NULL,
    unit_cost              numeric(14,2) NOT NULL,
    line_total             numeric(14,2) NOT NULL,
    stock_movement_id      uuid          NOT NULL,
    CONSTRAINT pk_goods_receipt_line PRIMARY KEY (id),
    CONSTRAINT fk_goods_receipt_line_goods_receipt FOREIGN KEY (goods_receipt_id)
        REFERENCES goods_receipt (id) ON DELETE RESTRICT,
    CONSTRAINT fk_goods_receipt_line_purchase_order_line FOREIGN KEY (purchase_order_line_id)
        REFERENCES purchase_order_line (id) ON DELETE RESTRICT,
    CONSTRAINT fk_goods_receipt_line_product FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE RESTRICT,
    CONSTRAINT fk_goods_receipt_line_stock_movement FOREIGN KEY (stock_movement_id)
        REFERENCES stock_movement (id) ON DELETE RESTRICT,
    CONSTRAINT ck_goods_receipt_line_line_number CHECK (line_number > 0),
    CONSTRAINT ck_goods_receipt_line_quantity CHECK (quantity > 0),
    CONSTRAINT ck_goods_receipt_line_unit_cost CHECK (unit_cost >= 0),
    CONSTRAINT ck_goods_receipt_line_total CHECK (line_total = quantity * unit_cost)
);

CREATE UNIQUE INDEX uk_goods_receipt_line_number ON goods_receipt_line (goods_receipt_id, line_number);
-- Un mouvement de stock justifie au plus une ligne de réception.
CREATE UNIQUE INDEX uk_goods_receipt_line_stock_movement_id ON goods_receipt_line (stock_movement_id);
CREATE INDEX ix_goods_receipt_line_purchase_order_line_id ON goods_receipt_line (purchase_order_line_id);
CREATE INDEX ix_goods_receipt_line_product_id ON goods_receipt_line (product_id);

COMMENT ON TABLE goods_receipt_line IS 'Lignes reçues, en ajout seul ; chacune désigne le mouvement de stock RECEIPT qu''elle a produit.';

-- --- Journal en ajout seul ----------------------------------------------------------------

CREATE TRIGGER trg_goods_receipt_prevent_modification
    BEFORE UPDATE OR DELETE ON goods_receipt
    FOR EACH ROW EXECUTE FUNCTION fn_prevent_modification();
CREATE TRIGGER trg_goods_receipt_prevent_truncate
    BEFORE TRUNCATE ON goods_receipt
    FOR EACH STATEMENT EXECUTE FUNCTION fn_prevent_modification();
CREATE TRIGGER trg_goods_receipt_line_prevent_modification
    BEFORE UPDATE OR DELETE ON goods_receipt_line
    FOR EACH ROW EXECUTE FUNCTION fn_prevent_modification();
CREATE TRIGGER trg_goods_receipt_line_prevent_truncate
    BEFORE TRUNCATE ON goods_receipt_line
    FOR EACH STATEMENT EXECUTE FUNCTION fn_prevent_modification();

-- --- Une ligne reçue = un mouvement de stock RECEIPT exactement correspondant --------------

CREATE FUNCTION fn_goods_receipt_line_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    r  goods_receipt%ROWTYPE;
    ol purchase_order_line%ROWTYPE;
    m  stock_movement%ROWTYPE;
    order_warehouse uuid;
BEGIN
    SELECT * INTO r FROM goods_receipt WHERE id = NEW.goods_receipt_id;
    SELECT * INTO ol FROM purchase_order_line WHERE id = NEW.purchase_order_line_id;
    SELECT * INTO m FROM stock_movement WHERE id = NEW.stock_movement_id;
    SELECT warehouse_id INTO order_warehouse FROM purchase_order WHERE id = r.purchase_order_id;

    IF r.id IS NULL OR ol.id IS NULL
       OR ol.purchase_order_id <> r.purchase_order_id
       OR ol.product_id <> NEW.product_id
       OR ol.unit_cost <> NEW.unit_cost
       OR order_warehouse <> r.warehouse_id THEN
        RAISE EXCEPTION 'goods_receipt_line : la ligne ne correspond pas à la commande réceptionnée.'
            USING ERRCODE = 'check_violation';
    END IF;

    IF m.id IS NULL
       OR m.type <> 'RECEIPT'
       OR m.source_type <> 'PURCHASE_ORDER'
       OR m.source_id IS DISTINCT FROM NEW.goods_receipt_id
       OR m.warehouse_id <> r.warehouse_id
       OR m.product_id <> NEW.product_id
       OR m.quantity <> NEW.quantity
       OR m.unit_cost IS DISTINCT FROM NEW.unit_cost THEN
        RAISE EXCEPTION 'goods_receipt_line : aucune entrée en stock ne correspond à cette ligne reçue (mouvement %).',
            NEW.stock_movement_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION fn_goods_receipt_line_guard() IS
    'Trigger de goods_receipt_line : une ligne reçue désigne le mouvement RECEIPT exact qu''elle a produit.';

CREATE TRIGGER trg_goods_receipt_line_guard
    BEFORE INSERT ON goods_receipt_line
    FOR EACH ROW EXECUTE FUNCTION fn_goods_receipt_line_guard();

-- --- Cohérence vérifiée au COMMIT (contraintes différées) ---------------------------------

CREATE FUNCTION fn_purchase_receipt_consistency() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    line_id    uuid;
    expected   integer;
    actual     integer;
    lines      integer;
    amount     numeric;
    r          goods_receipt%ROWTYPE;
BEGIN
    IF TG_TABLE_NAME = 'goods_receipt' THEN
        SELECT * INTO r FROM goods_receipt WHERE id = NEW.id;
        SELECT count(*), coalesce(sum(quantity), 0), coalesce(sum(line_total), 0)
          INTO lines, actual, amount
          FROM goods_receipt_line WHERE goods_receipt_id = NEW.id;
        IF lines = 0 OR actual <> r.total_quantity OR amount <> r.total_amount THEN
            RAISE EXCEPTION 'goods_receipt % : réception sans ligne ou totaux incohérents.', r.number
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NULL;
    END IF;

    -- IF plutôt que CASE : chaque branche ne cite que les colonnes de sa propre table.
    IF TG_TABLE_NAME = 'purchase_order_line' THEN
        line_id := NEW.id;
    ELSE
        line_id := NEW.purchase_order_line_id;
    END IF;
    SELECT received_quantity INTO expected FROM purchase_order_line WHERE id = line_id;
    SELECT coalesce(sum(quantity), 0) INTO actual FROM goods_receipt_line WHERE purchase_order_line_id = line_id;
    IF expected <> actual THEN
        RAISE EXCEPTION 'purchase_order_line % : quantité reçue % mais % réceptionnée(s) en stock.', line_id, expected, actual
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

COMMENT ON FUNCTION fn_purchase_receipt_consistency() IS
    'Contrainte différée : quantité reçue = somme des lignes de réception ; réception non vide et totaux cohérents.';

CREATE CONSTRAINT TRIGGER trg_purchase_order_line_received_consistency
    AFTER INSERT OR UPDATE OF received_quantity ON purchase_order_line
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION fn_purchase_receipt_consistency();

CREATE CONSTRAINT TRIGGER trg_goods_receipt_line_received_consistency
    AFTER INSERT ON goods_receipt_line
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION fn_purchase_receipt_consistency();

CREATE CONSTRAINT TRIGGER trg_goods_receipt_consistency
    AFTER INSERT ON goods_receipt
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION fn_purchase_receipt_consistency();
