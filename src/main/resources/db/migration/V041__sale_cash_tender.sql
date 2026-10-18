-- =============================================================================
-- V041 — Ventes : montant reçu du client et monnaie rendue (espèces)
--
-- Conservés pour vérification : cash_received (espèces remises par le client) et change_given
-- (monnaie rendue = cash_received − paid_amount, calculée par le serveur). Facultatifs (ventes
-- antérieures, paiement non espèces, montant non saisi) ; renseignés ensemble, en espèces
-- seulement, jamais inférieurs au montant payé. Ils n'entrent dans aucun total : la caisse
-- n'encaisse que paid_amount. Immuables après l'enregistrement, comme les autres montants.
--
-- Annulation : db/rollback/U041__sale_cash_tender.sql
-- =============================================================================

ALTER TABLE sale ADD COLUMN cash_received numeric(14,2);
ALTER TABLE sale ADD COLUMN change_given numeric(14,2);

ALTER TABLE sale ADD CONSTRAINT ck_sale_cash_tender CHECK (
    (cash_received IS NULL AND change_given IS NULL)
    OR (cash_received IS NOT NULL AND change_given IS NOT NULL
        AND payment_method = 'CASH'
        AND cash_received >= paid_amount
        AND change_given = cash_received - paid_amount)
);

COMMENT ON COLUMN sale.cash_received IS 'Espèces remises par le client (facultatif), pour vérification.';
COMMENT ON COLUMN sale.change_given IS 'Monnaie rendue = cash_received − paid_amount (calculée par le serveur).';

-- Immuables, comme les autres montants de la vente (fn_sale_guard, V017).
CREATE FUNCTION fn_sale_cash_tender_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.cash_received IS DISTINCT FROM OLD.cash_received
       OR NEW.change_given IS DISTINCT FROM OLD.change_given THEN
        RAISE EXCEPTION 'Montant reçu et monnaie rendue d''une vente non modifiables'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sale_cash_tender_guard
    BEFORE UPDATE ON sale
    FOR EACH ROW EXECUTE FUNCTION fn_sale_cash_tender_guard();
