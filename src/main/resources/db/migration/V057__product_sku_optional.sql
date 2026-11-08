-- SKU facultatif (formulaire de saisie, lot 3) : colonne nullable, contrainte de format et index unique
-- préservés (un SKU posé doit rester valide et unique).
ALTER TABLE product ALTER COLUMN sku DROP NOT NULL;
ALTER TABLE product DROP CONSTRAINT ck_product_sku_format;
ALTER TABLE product ADD CONSTRAINT ck_product_sku_format
    CHECK (sku IS NULL OR sku ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,49}$');
DROP INDEX uk_product_sku;
CREATE UNIQUE INDEX uk_product_sku ON product (lower(sku)) WHERE sku IS NOT NULL;
