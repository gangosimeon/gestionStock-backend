-- Annulation de V057 : SKU redevient obligatoire, contrainte et index restaurés.
-- Pour les tests de retour arrière : jamais en production.

-- Remplir les SKU manquants avant de remettre NOT NULL
UPDATE product SET sku = 'AUTO-' || substring(id::text, 1, 8) WHERE sku IS NULL;

ALTER TABLE product DROP CONSTRAINT IF EXISTS ck_product_sku_format;
ALTER TABLE product ADD CONSTRAINT ck_product_sku_format
    CHECK (sku ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,49}$');
DROP INDEX IF EXISTS uk_product_sku;
CREATE UNIQUE INDEX uk_product_sku ON product (lower(sku));
ALTER TABLE product ALTER COLUMN sku SET NOT NULL;

DELETE FROM flyway_schema_history WHERE version = '057';
