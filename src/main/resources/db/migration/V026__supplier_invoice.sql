-- V026 — Facture fournisseur d'une commande.
--
-- Une commande porte au plus une facture : ses colonnes sont rangées sur purchase_order (au lieu
-- de la table supplier_invoice prévue à l'origine), ce qui rend la relation 0..1 évidente et la
-- facture visible partout où la commande l'est. Règles (docs/supplier-invoices.md) :
--   * facturable seulement quand plus rien n'est attendu (commande reçue ou soldée) ;
--   * montant facturé = valeur reçue, calculé par le serveur ;
--   * numéro unique par fournisseur (sans tenir compte de la casse) ;
--   * une facture enregistrée ne se modifie plus.

ALTER TABLE purchase_order ADD COLUMN invoice_number varchar(50);
ALTER TABLE purchase_order ADD COLUMN invoice_date date;
ALTER TABLE purchase_order ADD COLUMN invoiced_amount numeric(14,2);
ALTER TABLE purchase_order ADD COLUMN invoiced_at timestamptz;
ALTER TABLE purchase_order ADD COLUMN invoiced_by uuid;

ALTER TABLE purchase_order ADD CONSTRAINT fk_purchase_order_invoiced_by
    FOREIGN KEY (invoiced_by) REFERENCES app_user (id) ON DELETE RESTRICT;
CREATE INDEX ix_purchase_order_invoiced_by ON purchase_order (invoiced_by) WHERE invoiced_by IS NOT NULL;

-- Facture complète ou absente ; seulement sur une commande qui n'attend plus rien ; montant = reçu.
ALTER TABLE purchase_order ADD CONSTRAINT ck_purchase_order_invoice CHECK (
    (invoice_number IS NULL) = (invoice_date IS NULL)
    AND (invoice_number IS NULL) = (invoiced_amount IS NULL)
    AND (invoice_number IS NULL) = (invoiced_at IS NULL)
    AND (invoice_number IS NULL OR (status IN ('RECEIVED', 'CLOSED') AND invoiced_amount = received_amount)));

CREATE UNIQUE INDEX uk_purchase_order_supplier_invoice
    ON purchase_order (supplier_id, lower(invoice_number)) WHERE invoice_number IS NOT NULL;

COMMENT ON COLUMN purchase_order.invoice_number IS 'Numéro de la facture du fournisseur (unique par fournisseur).';
COMMENT ON COLUMN purchase_order.invoice_date IS 'Date de la facture (ni future, ni antérieure à la commande).';
COMMENT ON COLUMN purchase_order.invoiced_amount IS 'Montant facturé : valeur reçue au moment de la facturation.';

-- Une facture enregistrée est figée.
CREATE FUNCTION fn_purchase_order_invoice_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.invoice_number IS NOT NULL AND (
            NEW.invoice_number IS DISTINCT FROM OLD.invoice_number
            OR NEW.invoice_date IS DISTINCT FROM OLD.invoice_date
            OR NEW.invoiced_amount IS DISTINCT FROM OLD.invoiced_amount
            OR NEW.invoiced_at IS DISTINCT FROM OLD.invoiced_at
            OR NEW.invoiced_by IS DISTINCT FROM OLD.invoiced_by) THEN
        RAISE EXCEPTION 'purchase_order % : la facture % est déjà enregistrée.', OLD.number, OLD.invoice_number
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION fn_purchase_order_invoice_guard() IS 'Trigger de purchase_order : facture enregistrée immuable.';

CREATE TRIGGER trg_purchase_order_invoice_guard
    BEFORE UPDATE ON purchase_order
    FOR EACH ROW EXECUTE FUNCTION fn_purchase_order_invoice_guard();
