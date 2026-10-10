-- Annule V014__supplier.sql — DÉTRUIT LES FOURNISSEURS (le fournisseur habituel des produits est perdu).
-- Prérequis : aucune table des achats ne référence encore supplier.
DELETE FROM role_permission rp
USING role r, permission p
WHERE rp.role_id = r.id AND rp.permission_id = p.id
  AND r.code = 'CAISSIER' AND p.code = 'supplier:read';

ALTER TABLE product DROP CONSTRAINT fk_product_default_supplier;
UPDATE product SET default_supplier_id = NULL WHERE default_supplier_id IS NOT NULL;
COMMENT ON COLUMN product.default_supplier_id IS 'Fournisseur habituel. Clé étrangère ajoutée avec le module fournisseur.';

DROP TABLE supplier;
DROP SEQUENCE seq_supplier_code;
DELETE FROM flyway_schema_history WHERE version = '014';
