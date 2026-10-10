-- =============================================================================
-- V019 — Inventaires
--
-- Workflow : DRAFT (périmètre modifiable) → IN_PROGRESS (périmètre figé) → COUNTING
-- (comptages saisis) → VALIDATION (comptage clos, en attente du valideur ; retour possible
-- en COUNTING) → COMPLETED (écarts appliqués au stock). CANCELLED possible avant la fin.
-- COMPLETED et CANCELLED sont définitifs : la base refuse toute modification.
--
-- inventory_session : un inventaire d'UN dépôt ; au plus un inventaire non terminé par dépôt
-- (deux inventaires simultanés ajusteraient deux fois les mêmes produits).
-- inventory_line : un produit du périmètre ; quantité théorique relevée par le serveur au
-- moment du comptage, quantité comptée, écart = compté − théorique, et le mouvement de stock
-- INVENTORY qui l'a appliqué.
--
-- L'ajustement applique l'ÉCART MESURÉ AU COMPTAGE (et non un réalignement sur le stock du
-- jour de la validation) : les ventes et réceptions faites entre comptage et validation sont
-- conservées.
--
-- Garde-fous :
--   * fn_inventory_session_guard : transitions autorisées uniquement ; inventaire terminé
--     ou annulé figé ; jamais de suppression ;
--   * fn_inventory_line_guard : lignes ajoutées ou retirées seulement en DRAFT, comptées
--     seulement en IN_PROGRESS / COUNTING, liées à leur mouvement seulement en VALIDATION ;
--   * fn_inventory_consistency (DIFFÉRÉE, au COMMIT) : un inventaire terminé a toutes ses
--     lignes comptées, et chaque écart non nul son mouvement INVENTORY exact.
--
-- Annulation : db/rollback/U019__inventory.sql
-- =============================================================================

CREATE SEQUENCE seq_inventory_number;

CREATE TABLE inventory_session (
    id                  uuid          NOT NULL DEFAULT gen_random_uuid(),
    number              varchar(30)   NOT NULL,
    warehouse_id        uuid          NOT NULL,
    status              varchar(20)   NOT NULL DEFAULT 'DRAFT',
    scope               varchar(20)   NOT NULL,
    category_id         uuid,
    note                varchar(1000),
    started_at          timestamptz,
    started_by          uuid,
    submitted_at        timestamptz,
    submitted_by        uuid,
    validated_at        timestamptz,
    validated_by        uuid,
    validation_comment  varchar(1000),
    cancelled_at        timestamptz,
    cancelled_by        uuid,
    cancellation_reason varchar(500),
    version             bigint        NOT NULL DEFAULT 0,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    created_by          uuid,
    updated_at          timestamptz   NOT NULL DEFAULT now(),
    updated_by          uuid,
    CONSTRAINT pk_inventory_session PRIMARY KEY (id),
    CONSTRAINT fk_inventory_session_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse (id) ON DELETE RESTRICT,
    CONSTRAINT fk_inventory_session_category FOREIGN KEY (category_id) REFERENCES category (id) ON DELETE RESTRICT,
    CONSTRAINT ck_inventory_session_status CHECK (status IN ('DRAFT', 'IN_PROGRESS', 'COUNTING', 'VALIDATION', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_inventory_session_scope CHECK (scope IN ('FULL', 'CATEGORY', 'SELECTION')),
    CONSTRAINT ck_inventory_session_category CHECK ((scope = 'CATEGORY') = (category_id IS NOT NULL)),
    CONSTRAINT ck_inventory_session_validated CHECK ((status = 'COMPLETED') = (validated_at IS NOT NULL AND validated_by IS NOT NULL)),
    CONSTRAINT ck_inventory_session_cancelled CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL))
);

CREATE UNIQUE INDEX uk_inventory_session_number ON inventory_session (number);
-- Au plus un inventaire non terminé par dépôt.
CREATE UNIQUE INDEX uk_inventory_session_open_warehouse ON inventory_session (warehouse_id)
    WHERE status NOT IN ('COMPLETED', 'CANCELLED');
CREATE INDEX ix_inventory_session_warehouse_id_created_at ON inventory_session (warehouse_id, created_at DESC);
CREATE INDEX ix_inventory_session_category_id ON inventory_session (category_id);
CREATE INDEX ix_inventory_session_status ON inventory_session (status);

COMMENT ON TABLE inventory_session IS 'Inventaires physiques d''un dépôt, du brouillon à la validation. Terminés : figés.';
COMMENT ON COLUMN inventory_session.number IS 'Numéro lisible INV-AAAA-nnnnnn (séquence seq_inventory_number).';
COMMENT ON COLUMN inventory_session.scope IS 'Périmètre : FULL (produits actifs), CATEGORY (une catégorie), SELECTION (liste choisie).';

CREATE TABLE inventory_line (
    id                   uuid          NOT NULL DEFAULT gen_random_uuid(),
    inventory_id         uuid          NOT NULL,
    line_number          integer       NOT NULL,
    product_id           uuid          NOT NULL,
    product_sku          varchar(50)   NOT NULL,
    product_name         varchar(200)  NOT NULL,
    theoretical_quantity integer,
    counted_quantity     integer,
    difference           integer,
    counted_at           timestamptz,
    counted_by           uuid,
    note                 varchar(500),
    stock_movement_id    uuid,
    CONSTRAINT pk_inventory_line PRIMARY KEY (id),
    CONSTRAINT fk_inventory_line_inventory FOREIGN KEY (inventory_id) REFERENCES inventory_session (id) ON DELETE RESTRICT,
    CONSTRAINT fk_inventory_line_product FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE RESTRICT,
    CONSTRAINT fk_inventory_line_stock_movement FOREIGN KEY (stock_movement_id) REFERENCES stock_movement (id) ON DELETE RESTRICT,
    CONSTRAINT ck_inventory_line_line_number CHECK (line_number > 0),
    CONSTRAINT ck_inventory_line_quantities CHECK (theoretical_quantity IS NULL OR theoretical_quantity >= 0),
    -- Une ligne est comptée en entier : compté, théorique, écart, date et auteur ensemble.
    CONSTRAINT ck_inventory_line_count CHECK (
        (counted_quantity IS NULL AND theoretical_quantity IS NULL AND difference IS NULL
             AND counted_at IS NULL AND counted_by IS NULL)
        OR (counted_quantity >= 0 AND theoretical_quantity IS NOT NULL AND counted_at IS NOT NULL
             AND counted_by IS NOT NULL AND difference = counted_quantity - theoretical_quantity)),
    CONSTRAINT ck_inventory_line_movement CHECK (stock_movement_id IS NULL OR (difference IS NOT NULL AND difference <> 0))
);

CREATE UNIQUE INDEX uk_inventory_line_number ON inventory_line (inventory_id, line_number);
CREATE UNIQUE INDEX uk_inventory_line_product ON inventory_line (inventory_id, product_id);
CREATE UNIQUE INDEX uk_inventory_line_stock_movement_id ON inventory_line (stock_movement_id) WHERE stock_movement_id IS NOT NULL;
CREATE INDEX ix_inventory_line_product_id ON inventory_line (product_id);

COMMENT ON TABLE inventory_line IS 'Lignes d''inventaire : théorique (relevé au comptage), compté, écart et mouvement appliqué.';
COMMENT ON COLUMN inventory_line.theoretical_quantity IS 'Stock du dépôt relevé par le serveur au moment du comptage.';
COMMENT ON COLUMN inventory_line.difference IS 'Écart = compté − théorique, appliqué au stock à la validation.';

CREATE TRIGGER trg_inventory_session_prevent_delete
    BEFORE DELETE ON inventory_session
    FOR EACH ROW EXECUTE FUNCTION fn_prevent_modification();
CREATE TRIGGER trg_inventory_session_prevent_truncate
    BEFORE TRUNCATE ON inventory_session
    FOR EACH STATEMENT EXECUTE FUNCTION fn_prevent_modification();
CREATE TRIGGER trg_inventory_line_prevent_truncate
    BEFORE TRUNCATE ON inventory_line
    FOR EACH STATEMENT EXECUTE FUNCTION fn_prevent_modification();

-- --- Transitions et inventaire terminé figé -------------------------------------------------

CREATE FUNCTION fn_inventory_session_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status IN ('COMPLETED', 'CANCELLED') THEN
        RAISE EXCEPTION 'inventory_session % : un inventaire terminé ou annulé ne se modifie plus.', OLD.number
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.number <> OLD.number OR NEW.warehouse_id <> OLD.warehouse_id OR NEW.scope <> OLD.scope
       OR NEW.category_id IS DISTINCT FROM OLD.category_id THEN
        RAISE EXCEPTION 'inventory_session % : numéro, dépôt et périmètre sont immuables.', OLD.number
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.status <> OLD.status AND NOT (
           (OLD.status = 'DRAFT' AND NEW.status IN ('IN_PROGRESS', 'CANCELLED'))
        OR (OLD.status = 'IN_PROGRESS' AND NEW.status IN ('COUNTING', 'CANCELLED'))
        OR (OLD.status = 'COUNTING' AND NEW.status IN ('VALIDATION', 'CANCELLED'))
        OR (OLD.status = 'VALIDATION' AND NEW.status IN ('COMPLETED', 'COUNTING', 'CANCELLED'))) THEN
        RAISE EXCEPTION 'inventory_session % : passage % → % interdit.', OLD.number, OLD.status, NEW.status
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION fn_inventory_session_guard() IS 'Trigger de inventory_session : transitions autorisées, inventaire terminé figé.';

CREATE TRIGGER trg_inventory_session_guard
    BEFORE UPDATE ON inventory_session
    FOR EACH ROW EXECUTE FUNCTION fn_inventory_session_guard();

-- --- Lignes : modifiables seulement à l'étape prévue -----------------------------------------

CREATE FUNCTION fn_inventory_line_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    st varchar(20);
BEGIN
    IF TG_OP = 'DELETE' THEN
        SELECT status INTO st FROM inventory_session WHERE id = OLD.inventory_id;
        IF st IS DISTINCT FROM 'DRAFT' THEN
            RAISE EXCEPTION 'inventory_line : produit retiré hors brouillon (inventaire %).', st
                USING ERRCODE = 'restrict_violation';
        END IF;
        RETURN OLD;
    END IF;

    SELECT status INTO st FROM inventory_session WHERE id = NEW.inventory_id;
    IF TG_OP = 'INSERT' THEN
        IF st <> 'DRAFT' THEN
            RAISE EXCEPTION 'inventory_line : produit ajouté hors brouillon (inventaire %).', st
                USING ERRCODE = 'restrict_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.inventory_id <> OLD.inventory_id OR NEW.product_id <> OLD.product_id OR NEW.line_number <> OLD.line_number THEN
        RAISE EXCEPTION 'inventory_line : produit et numéro de ligne immuables.'
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF (NEW.counted_quantity IS DISTINCT FROM OLD.counted_quantity
        OR NEW.theoretical_quantity IS DISTINCT FROM OLD.theoretical_quantity
        OR NEW.note IS DISTINCT FROM OLD.note) AND st NOT IN ('IN_PROGRESS', 'COUNTING') THEN
        RAISE EXCEPTION 'inventory_line : comptage modifié hors comptage (inventaire %).', st
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.stock_movement_id IS DISTINCT FROM OLD.stock_movement_id
       AND (st <> 'VALIDATION' OR OLD.stock_movement_id IS NOT NULL) THEN
        RAISE EXCEPTION 'inventory_line : mouvement rattaché hors validation (inventaire %).', st
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION fn_inventory_line_guard() IS 'Trigger de inventory_line : ajout/retrait en brouillon, comptage en cours, mouvement en validation.';

CREATE TRIGGER trg_inventory_line_guard
    BEFORE INSERT OR UPDATE OR DELETE ON inventory_line
    FOR EACH ROW EXECUTE FUNCTION fn_inventory_line_guard();

-- --- Inventaire terminé ⇒ écarts appliqués exactement (vérifié au COMMIT) ----------------------

CREATE FUNCTION fn_inventory_consistency() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    s   inventory_session%ROWTYPE;
    bad integer;
BEGIN
    SELECT * INTO s FROM inventory_session WHERE id = NEW.id;
    IF s.status <> 'COMPLETED' THEN
        RETURN NULL;
    END IF;
    SELECT count(*) INTO bad
      FROM inventory_line l
      LEFT JOIN stock_movement m ON m.id = l.stock_movement_id
     WHERE l.inventory_id = s.id
       AND (l.counted_quantity IS NULL
            OR (l.difference = 0 AND l.stock_movement_id IS NOT NULL)
            OR (l.difference <> 0 AND (m.id IS NULL OR m.type <> 'INVENTORY' OR m.source_type <> 'INVENTORY_SESSION'
                                        OR m.source_id <> s.id OR m.warehouse_id <> s.warehouse_id
                                        OR m.product_id <> l.product_id OR m.quantity <> l.difference)));
    IF bad > 0 THEN
        RAISE EXCEPTION 'inventory_session % : % ligne(s) non comptée(s) ou écart non appliqué exactement au stock.',
            s.number, bad
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

COMMENT ON FUNCTION fn_inventory_consistency() IS
    'Contrainte différée : inventaire terminé ⇒ lignes comptées et chaque écart appliqué par son mouvement INVENTORY.';

CREATE CONSTRAINT TRIGGER trg_inventory_consistency
    AFTER UPDATE OF status ON inventory_session
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION fn_inventory_consistency();
