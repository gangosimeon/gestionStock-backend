-- =============================================================================
-- V013 — Stock : niveaux par dépôt et journal des mouvements
--
-- Source de vérité UNIQUE de la quantité disponible : stock_level (une ligne par
-- couple dépôt × produit). Aucune autre table ne porte de quantité en stock (le
-- produit n'en a pas, cf. V011).
--
-- stock_movement est le journal, en ajout seul (trigger fn_prevent_modification) :
-- chaque variation de stock_level correspond à exactement un mouvement.
--
-- Garde-fou en base (fn_stock_level_guard) : une ligne de stock_level ne peut être
-- créée qu'à zéro, ne peut jamais être supprimée, et ne peut changer que si elle
-- désigne un NOUVEAU mouvement du même dépôt et du même produit dont la quantité
-- explique exactement la variation. Une écriture directe (UPDATE stock_level SET
-- quantity = …) est donc refusée par PostgreSQL lui-même, quel que soit le code
-- appelant. Invariant : quantity = somme des mouvements du couple.
--
-- Annulation : db/rollback/U013__stock.sql
-- =============================================================================

CREATE TABLE stock_movement (
    id               uuid          NOT NULL,
    warehouse_id     uuid          NOT NULL,
    product_id       uuid          NOT NULL,
    type             varchar(30)   NOT NULL,
    quantity         integer       NOT NULL,
    balance_after    integer       NOT NULL,
    unit_cost        numeric(14,2),
    reason           varchar(500),
    source_type      varchar(30)   NOT NULL,
    source_id        uuid,
    source_reference varchar(100),
    correlation_id   uuid,
    reversal_of_id   uuid,
    created_at       timestamptz   NOT NULL DEFAULT now(),
    created_by       uuid          NOT NULL,
    CONSTRAINT pk_stock_movement PRIMARY KEY (id),
    CONSTRAINT fk_stock_movement_warehouse FOREIGN KEY (warehouse_id)
        REFERENCES warehouse (id) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_movement_product FOREIGN KEY (product_id)
        REFERENCES product (id) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_movement_created_by FOREIGN KEY (created_by)
        REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_movement_reversal_of FOREIGN KEY (reversal_of_id)
        REFERENCES stock_movement (id) ON DELETE RESTRICT,
    CONSTRAINT ck_stock_movement_type CHECK (type IN (
        'OPENING', 'RECEIPT', 'CUSTOMER_RETURN', 'SALE_CANCELLATION', 'TRANSFER_IN',
        'SALE', 'SUPPLIER_RETURN', 'LOSS', 'TRANSFER_OUT',
        'ADJUSTMENT', 'INVENTORY', 'REVERSAL')),
    CONSTRAINT ck_stock_movement_quantity_not_zero CHECK (quantity <> 0),
    -- Sens imposé par le type : entrées positives, sorties négatives.
    CONSTRAINT ck_stock_movement_direction CHECK (
        (type IN ('OPENING', 'RECEIPT', 'CUSTOMER_RETURN', 'SALE_CANCELLATION', 'TRANSFER_IN') AND quantity > 0)
        OR (type IN ('SALE', 'SUPPLIER_RETURN', 'LOSS', 'TRANSFER_OUT') AND quantity < 0)
        OR type IN ('ADJUSTMENT', 'INVENTORY', 'REVERSAL')),
    CONSTRAINT ck_stock_movement_balance_after CHECK (balance_after >= 0),
    CONSTRAINT ck_stock_movement_unit_cost CHECK (unit_cost IS NULL OR unit_cost >= 0),
    CONSTRAINT ck_stock_movement_source_type_format CHECK (source_type ~ '^[A-Z][A-Z0-9_]*$'),
    CONSTRAINT ck_stock_movement_reversal CHECK ((type = 'REVERSAL') = (reversal_of_id IS NOT NULL))
);

-- Historique par dépôt (écran Stock) et par produit (fiche produit), du plus récent au plus ancien.
CREATE INDEX ix_stock_movement_warehouse_id_created_at ON stock_movement (warehouse_id, created_at DESC);
CREATE INDEX ix_stock_movement_product_id_created_at ON stock_movement (product_id, created_at DESC);
CREATE INDEX ix_stock_movement_created_by ON stock_movement (created_by);
CREATE INDEX ix_stock_movement_source ON stock_movement (source_type, source_id);
CREATE INDEX ix_stock_movement_correlation_id ON stock_movement (correlation_id) WHERE correlation_id IS NOT NULL;
-- Un mouvement ne peut être annulé qu'une fois.
CREATE UNIQUE INDEX uk_stock_movement_reversal_of_id ON stock_movement (reversal_of_id) WHERE reversal_of_id IS NOT NULL;

CREATE TRIGGER trg_stock_movement_prevent_modification
    BEFORE UPDATE OR DELETE ON stock_movement
    FOR EACH ROW EXECUTE FUNCTION fn_prevent_modification();

CREATE TRIGGER trg_stock_movement_prevent_truncate
    BEFORE TRUNCATE ON stock_movement
    FOR EACH STATEMENT EXECUTE FUNCTION fn_prevent_modification();

COMMENT ON TABLE stock_movement IS 'Journal des mouvements de stock, en ajout seul. Seule cause de variation de stock_level.';
COMMENT ON COLUMN stock_movement.quantity IS 'Variation signée : positive = entrée, négative = sortie.';
COMMENT ON COLUMN stock_movement.balance_after IS 'Quantité du couple dépôt × produit après ce mouvement.';
COMMENT ON COLUMN stock_movement.unit_cost IS 'Coût unitaire au moment du mouvement (valorisation).';
COMMENT ON COLUMN stock_movement.reason IS 'Motif saisi ou généré (note du front).';
COMMENT ON COLUMN stock_movement.source_type IS 'Pièce à l''origine du mouvement : MANUAL, PURCHASE_ORDER, SALE, TRANSFER, INVENTORY_SESSION…';
COMMENT ON COLUMN stock_movement.correlation_id IS 'Regroupe les mouvements d''une même opération (ex. les deux jambes d''un transfert).';
COMMENT ON COLUMN stock_movement.reversal_of_id IS 'Mouvement annulé par ce mouvement de type REVERSAL.';

CREATE TABLE stock_level (
    warehouse_id     uuid        NOT NULL,
    product_id       uuid        NOT NULL,
    quantity         integer     NOT NULL DEFAULT 0,
    last_movement_id uuid,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_stock_level PRIMARY KEY (warehouse_id, product_id),
    CONSTRAINT fk_stock_level_warehouse FOREIGN KEY (warehouse_id)
        REFERENCES warehouse (id) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_level_product FOREIGN KEY (product_id)
        REFERENCES product (id) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_level_last_movement FOREIGN KEY (last_movement_id)
        REFERENCES stock_movement (id) ON DELETE RESTRICT,
    -- Pas de stock négatif : filet de sécurité derrière le contrôle applicatif.
    CONSTRAINT ck_stock_level_quantity CHECK (quantity >= 0)
);

CREATE INDEX ix_stock_level_product_id ON stock_level (product_id);
CREATE INDEX ix_stock_level_last_movement_id ON stock_level (last_movement_id);

CREATE FUNCTION fn_stock_level_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    m stock_movement%ROWTYPE;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.quantity <> 0 OR NEW.last_movement_id IS NOT NULL THEN
            RAISE EXCEPTION 'stock_level : une ligne est créée à zéro, le stock n''évolue que par mouvement.'
                USING ERRCODE = 'restrict_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.warehouse_id <> OLD.warehouse_id OR NEW.product_id <> OLD.product_id THEN
        RAISE EXCEPTION 'stock_level : le couple dépôt × produit est immuable.'
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.last_movement_id IS NOT DISTINCT FROM OLD.last_movement_id THEN
        IF NEW.quantity <> OLD.quantity THEN
            RAISE EXCEPTION 'stock_level : modification directe interdite, passer par un mouvement de stock.'
                USING ERRCODE = 'restrict_violation';
        END IF;
        RETURN NEW;
    END IF;

    SELECT * INTO m FROM stock_movement WHERE id = NEW.last_movement_id;
    IF NOT FOUND
       OR m.warehouse_id <> NEW.warehouse_id
       OR m.product_id <> NEW.product_id
       OR NEW.quantity <> OLD.quantity + m.quantity
       OR m.balance_after <> NEW.quantity THEN
        RAISE EXCEPTION 'stock_level : la variation ne correspond pas au mouvement %.', NEW.last_movement_id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION fn_stock_level_guard() IS
    'Trigger de stock_level : création à zéro, variation uniquement justifiée par un nouveau mouvement.';

CREATE TRIGGER trg_stock_level_guard
    BEFORE INSERT OR UPDATE ON stock_level
    FOR EACH ROW EXECUTE FUNCTION fn_stock_level_guard();

CREATE TRIGGER trg_stock_level_prevent_delete
    BEFORE DELETE ON stock_level
    FOR EACH ROW EXECUTE FUNCTION fn_prevent_modification();

CREATE TRIGGER trg_stock_level_prevent_truncate
    BEFORE TRUNCATE ON stock_level
    FOR EACH STATEMENT EXECUTE FUNCTION fn_prevent_modification();

COMMENT ON TABLE stock_level IS 'Quantité disponible par dépôt et par produit : source de vérité unique du stock.';
COMMENT ON COLUMN stock_level.last_movement_id IS 'Dernier mouvement appliqué ; toute variation doit en désigner un nouveau.';
