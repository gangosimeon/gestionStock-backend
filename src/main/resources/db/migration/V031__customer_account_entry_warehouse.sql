-- =============================================================================
-- V031 — Dépôt des écritures du compte client
--
-- Le client et sa dette restent communs à l'entreprise (solde et plafond globaux). Chaque
-- écriture est désormais rattachée au dépôt où elle est née, pour limiter le relevé détaillé
-- au périmètre de l'utilisateur.
--
-- Rattachement des écritures existantes, uniquement depuis leur pièce (aucune supposition, jamais
-- le dépôt principal par défaut) :
--   * vente à crédit et annulation (source SALE)        → dépôt de la vente ;
--   * avoir (source CUSTOMER_RETURN)                     → dépôt du retour ;
--   * règlement (source CUSTOMER_PAYMENT)                → dépôt de la caisse qui l'a encaissé.
-- Une écriture dont la pièce est introuvable garde warehouse_id NULL (visible seulement avec
-- warehouse:all, comptée dans « Autres dépôts » sinon). Contrôle après migration :
--   SELECT id, source_type, source_reference FROM customer_account_entry WHERE warehouse_id IS NULL;
-- La colonne reste nullable tant que ces cas n'ont pas été examinés ; toute nouvelle écriture,
-- elle, porte obligatoirement le dépôt de sa pièce (contrainte différée ci-dessous).
--
-- La table est en ajout seul : le garde-fou est suspendu le temps du rattachement, dans cette
-- transaction, puis rétabli. Seule la nouvelle colonne est écrite ; montants, soldes et
-- références sont inchangés.
-- =============================================================================

ALTER TABLE customer_account_entry ADD COLUMN warehouse_id uuid;
ALTER TABLE customer_account_entry
    ADD CONSTRAINT fk_customer_account_entry_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse (id)
        ON DELETE RESTRICT;

COMMENT ON COLUMN customer_account_entry.warehouse_id IS
    'Dépôt de la pièce à l''origine de l''écriture (vente, retour, caisse du règlement). NULL : historique non rattaché.';

ALTER TABLE customer_account_entry DISABLE TRIGGER trg_customer_account_entry_prevent_modification;

UPDATE customer_account_entry e
SET warehouse_id = s.warehouse_id
FROM sale s
WHERE e.source_type = 'SALE' AND s.id = e.source_id;

UPDATE customer_account_entry e
SET warehouse_id = r.warehouse_id
FROM customer_return r
WHERE e.source_type = 'CUSTOMER_RETURN' AND r.id = e.source_id;

UPDATE customer_account_entry e
SET warehouse_id = cs.warehouse_id
FROM cash_entry c
JOIN cash_session cs ON cs.id = c.session_id
WHERE e.source_type = 'CUSTOMER_PAYMENT' AND c.entry_type = 'CUSTOMER_PAYMENT' AND c.source_id = e.source_id;

ALTER TABLE customer_account_entry ENABLE TRIGGER trg_customer_account_entry_prevent_modification;

CREATE INDEX ix_customer_account_entry_customer_id_warehouse_id ON customer_account_entry (customer_id, warehouse_id);
CREATE INDEX ix_customer_account_entry_warehouse_id ON customer_account_entry (warehouse_id);

-- --- Nouvelle écriture : dépôt obligatoire, celui de sa pièce (vérifié au COMMIT) ---------------------

CREATE FUNCTION fn_customer_account_entry_warehouse() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    expected uuid;
BEGIN
    IF NEW.warehouse_id IS NULL THEN
        RAISE EXCEPTION 'customer_account_entry : le dépôt de l''écriture % est obligatoire.',
            coalesce(NEW.source_reference, NEW.source_id::text)
            USING ERRCODE = 'not_null_violation';
    END IF;
    expected := CASE NEW.source_type
        WHEN 'SALE' THEN (SELECT warehouse_id FROM sale WHERE id = NEW.source_id)
        WHEN 'CUSTOMER_RETURN' THEN (SELECT warehouse_id FROM customer_return WHERE id = NEW.source_id)
        WHEN 'CUSTOMER_PAYMENT' THEN (
            SELECT cs.warehouse_id FROM cash_entry c JOIN cash_session cs ON cs.id = c.session_id
            WHERE c.entry_type = 'CUSTOMER_PAYMENT' AND c.source_id = NEW.source_id)
    END;
    IF expected IS NOT NULL AND expected <> NEW.warehouse_id THEN
        RAISE EXCEPTION 'customer_account_entry : l''écriture % n''est pas dans le dépôt de sa pièce.',
            coalesce(NEW.source_reference, NEW.source_id::text)
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

COMMENT ON FUNCTION fn_customer_account_entry_warehouse() IS
    'Contrainte différée : toute nouvelle écriture du compte client porte le dépôt de sa pièce.';

CREATE CONSTRAINT TRIGGER trg_customer_account_entry_warehouse
    AFTER INSERT ON customer_account_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION fn_customer_account_entry_warehouse();
