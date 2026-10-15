-- =============================================================================
-- V032 — Dépôt des paiements fournisseurs
--
-- Un paiement fournisseur appartient à un dépôt : il ne s'impute que sur les commandes de ce
-- dépôt et, en espèces, sort d'une caisse de ce dépôt (D7).
--
-- Rattachement des paiements existants, sans jamais choisir entre plusieurs candidats ni prendre
-- le dépôt principal par défaut : un paiement reçoit le dépôt de ses commandes si elles sont
-- toutes du même dépôt ET, pour un paiement en espèces, si sa sortie de caisse vient de ce même
-- dépôt. Sinon (commandes de plusieurs dépôts, caisse d'un autre dépôt), il reste NULL : visible
-- seulement avec warehouse:all, à examiner. Contrôle après migration :
--   SELECT p.number, p.payment_date, p.amount FROM supplier_payment p WHERE p.warehouse_id IS NULL;
-- La colonne reste nullable tant que ces cas n'ont pas été traités ; tout nouveau paiement porte
-- obligatoirement son dépôt (contrainte différée ci-dessous).
--
-- La table est en ajout seul : le garde-fou est suspendu le temps du rattachement, dans cette
-- transaction, puis rétabli. Seule la nouvelle colonne est écrite ; montants, imputations et
-- références sont inchangés.
-- =============================================================================

ALTER TABLE supplier_payment ADD COLUMN warehouse_id uuid;
ALTER TABLE supplier_payment
    ADD CONSTRAINT fk_supplier_payment_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse (id)
        ON DELETE RESTRICT;

COMMENT ON COLUMN supplier_payment.warehouse_id IS
    'Dépôt du paiement : celui de ses commandes et de sa caisse. NULL : historique ambigu, non rattaché.';

ALTER TABLE supplier_payment DISABLE TRIGGER trg_supplier_payment_prevent_modification;

UPDATE supplier_payment p
SET warehouse_id = x.order_warehouse
FROM (
    SELECT a.payment_id, min(o.warehouse_id::text)::uuid AS order_warehouse,
           count(DISTINCT o.warehouse_id) AS warehouses
    FROM supplier_payment_allocation a
    JOIN purchase_order o ON o.id = a.purchase_order_id
    GROUP BY a.payment_id) x
WHERE x.payment_id = p.id
  AND x.warehouses = 1
  AND NOT EXISTS (
        SELECT 1 FROM cash_entry c JOIN cash_session cs ON cs.id = c.session_id
        WHERE c.entry_type = 'SUPPLIER_PAYMENT' AND c.source_id = p.id AND cs.warehouse_id <> x.order_warehouse);

ALTER TABLE supplier_payment ENABLE TRIGGER trg_supplier_payment_prevent_modification;

CREATE INDEX ix_supplier_payment_supplier_id_warehouse_id ON supplier_payment (supplier_id, warehouse_id);
CREATE INDEX ix_supplier_payment_warehouse_id ON supplier_payment (warehouse_id);

-- --- Nouveau paiement : dépôt obligatoire, celui de ses commandes et de sa caisse (vérifié au COMMIT) -----
-- Contrainte distincte de fn_supplier_payment_consistency (V025), laissée telle quelle.

CREATE FUNCTION fn_supplier_payment_warehouse() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    p supplier_payment%ROWTYPE;
BEGIN
    SELECT * INTO p FROM supplier_payment WHERE id = NEW.id;
    IF p.warehouse_id IS NULL THEN
        RAISE EXCEPTION 'supplier_payment % : le dépôt du paiement est obligatoire.', p.number
            USING ERRCODE = 'not_null_violation';
    END IF;
    IF EXISTS (SELECT 1 FROM supplier_payment_allocation a JOIN purchase_order o ON o.id = a.purchase_order_id
               WHERE a.payment_id = p.id AND o.warehouse_id <> p.warehouse_id) THEN
        RAISE EXCEPTION 'supplier_payment % : imputé sur la commande d''un autre dépôt.', p.number
            USING ERRCODE = 'check_violation';
    END IF;
    IF EXISTS (SELECT 1 FROM cash_entry c JOIN cash_session cs ON cs.id = c.session_id
               WHERE c.entry_type = 'SUPPLIER_PAYMENT' AND c.source_id = p.id AND cs.warehouse_id <> p.warehouse_id) THEN
        RAISE EXCEPTION 'supplier_payment % : sorti de la caisse d''un autre dépôt.', p.number
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

COMMENT ON FUNCTION fn_supplier_payment_warehouse() IS
    'Contrainte différée : nouveau paiement fournisseur avec son dépôt, celui de ses commandes et de sa caisse.';

CREATE CONSTRAINT TRIGGER trg_supplier_payment_warehouse
    AFTER INSERT ON supplier_payment
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION fn_supplier_payment_warehouse();
