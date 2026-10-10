-- =============================================================================================
-- V022 : journal d'audit — module fonctionnel.
--
-- Chaque entrée porte désormais le module concerné (AUTH, USER, SECURITY, CATALOG, STOCK,
-- PURCHASE, SALE…) pour filtrer le journal par domaine. Les entrées existantes sont classées
-- d'après leur type d'entité. Le journal reste en ajout seul : le trigger de V006 est
-- suspendu le temps de cette reprise, puis rétabli dans la même transaction.
-- =============================================================================================

ALTER TABLE audit_log ADD COLUMN module varchar(30);

ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_prevent_modification;

UPDATE audit_log SET module = CASE
    WHEN action IN ('LOGIN', 'LOGOUT', 'PASSWORD_CHANGE') THEN 'AUTH'
    WHEN entity_type = 'USER' THEN 'USER'
    WHEN entity_type IN ('ROLE', 'PERMISSION') THEN 'SECURITY'
    WHEN entity_type IN ('PRODUCT', 'CATEGORY', 'BRAND') THEN 'CATALOG'
    WHEN entity_type IN ('STOCK_MOVEMENT', 'WAREHOUSE_TRANSFER') THEN 'STOCK'
    WHEN entity_type = 'WAREHOUSE' THEN 'WAREHOUSE'
    WHEN entity_type = 'SUPPLIER' THEN 'SUPPLIER'
    WHEN entity_type IN ('PURCHASE_ORDER', 'GOODS_RECEIPT') THEN 'PURCHASE'
    WHEN entity_type = 'CUSTOMER' THEN 'CUSTOMER'
    WHEN entity_type = 'SALE' THEN 'SALE'
    WHEN entity_type = 'CUSTOMER_RETURN' THEN 'RETURN'
    WHEN entity_type = 'INVENTORY_SESSION' THEN 'INVENTORY'
    WHEN entity_type IN ('CASH_SESSION', 'CASH_ENTRY', 'CASH_REGISTER_SESSION') THEN 'CASH'
    WHEN entity_type = 'ALERT_SETTING' THEN 'ALERT'
    WHEN entity_type = 'AUDIT_LOG' THEN 'AUDIT'
    ELSE 'SYSTEM'
END;

ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_prevent_modification;

ALTER TABLE audit_log ALTER COLUMN module SET NOT NULL;
ALTER TABLE audit_log ADD CONSTRAINT ck_audit_log_module_format CHECK (module ~ '^[A-Z][A-Z0-9_]*$');

CREATE INDEX ix_audit_log_module_created_at ON audit_log (module, created_at DESC);
CREATE INDEX ix_audit_log_status_created_at ON audit_log (status, created_at DESC);

COMMENT ON COLUMN audit_log.module IS 'Module fonctionnel (AUTH, USER, SECURITY, CATALOG, STOCK, PURCHASE, SALE, RETURN, INVENTORY, CASH…).';
COMMENT ON COLUMN audit_log.user_id IS 'Auteur, relevé côté serveur dans le contexte de sécurité (jamais fourni par le client) ; nul pour un échec de connexion.';
COMMENT ON COLUMN audit_log.meta IS 'Contexte complémentaire, par exemple {"errorCode", "httpStatus"} pour un échec.';
