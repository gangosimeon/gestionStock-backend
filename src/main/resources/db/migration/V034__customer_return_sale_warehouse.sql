-- =============================================================================
-- V034 — Un retour client appartient au dépôt de sa vente
--
-- L'application créait déjà le retour dans le dépôt de la vente (sale.warehouse_id). La base le
-- garantit désormais. Le dépôt d'une vente ne change jamais (V017) et un retour est en ajout seul
-- (V020) : un contrôle à l'insertion suffit. Données existantes vérifiées avant la mise en place
-- (aucun retour dans un autre dépôt que sa vente).
-- =============================================================================

CREATE FUNCTION fn_customer_return_sale_warehouse() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.warehouse_id IS DISTINCT FROM (SELECT warehouse_id FROM sale WHERE id = NEW.sale_id) THEN
        RAISE EXCEPTION 'customer_return % : le retour doit être dans le dépôt de sa vente.', NEW.number
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION fn_customer_return_sale_warehouse() IS
    'Un retour client est enregistré dans le dépôt de sa vente.';

CREATE TRIGGER trg_customer_return_sale_warehouse
    BEFORE INSERT ON customer_return
    FOR EACH ROW EXECUTE FUNCTION fn_customer_return_sale_warehouse();
