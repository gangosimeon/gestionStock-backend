-- =============================================================================
-- V030 — Une caisse ne sert que son dépôt
--
-- L'application refusait déjà d'encaisser une vente, un retour ou une dépense dans une caisse
-- ouverte dans un autre dépôt (CASH_SESSION_OTHER_WAREHOUSE). La base le garantit désormais :
-- une écriture de caisse liée à une pièce de dépôt doit être dans une caisse de ce dépôt.
--
-- Contrainte différée (vérifiée au COMMIT) : la pièce et son écriture de caisse s'écrivent dans
-- la même transaction, dans un ordre quelconque. Seules les nouvelles écritures sont contrôlées ;
-- les écritures existantes ont été vérifiées avant la mise en place (aucune en défaut).
-- Les règlements clients et fournisseurs n'ont pas de pièce de dépôt (voir lots suivants).
-- =============================================================================

CREATE FUNCTION fn_cash_entry_warehouse_consistency() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    session_warehouse uuid;
    document_warehouse uuid;
BEGIN
    SELECT warehouse_id INTO session_warehouse FROM cash_session WHERE id = NEW.session_id;
    document_warehouse := CASE NEW.source_type
        WHEN 'SALE' THEN (SELECT warehouse_id FROM sale WHERE id = NEW.source_id)
        WHEN 'CUSTOMER_RETURN' THEN (SELECT warehouse_id FROM customer_return WHERE id = NEW.source_id)
        WHEN 'EXPENSE' THEN (SELECT warehouse_id FROM expense WHERE id = NEW.source_id)
    END;
    IF document_warehouse IS NOT NULL AND document_warehouse <> session_warehouse THEN
        RAISE EXCEPTION 'cash_entry % : la pièce % est d''un autre dépôt que la caisse.', NEW.number,
            coalesce(NEW.source_reference, NEW.source_id::text)
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

COMMENT ON FUNCTION fn_cash_entry_warehouse_consistency() IS
    'Contrainte différée : vente, retour ou dépense encaissés dans une caisse de leur dépôt.';

CREATE CONSTRAINT TRIGGER trg_cash_entry_warehouse_consistency
    AFTER INSERT ON cash_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION fn_cash_entry_warehouse_consistency();
