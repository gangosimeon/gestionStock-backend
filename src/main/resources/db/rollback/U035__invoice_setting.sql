-- Retour arrière de V035 (configuration de la facture) — SUPPRIME LA CONFIGURATION ET LE LOGO.

DELETE FROM role_permission
WHERE permission_id = (SELECT id FROM permission WHERE code = 'settings:manage');
DELETE FROM permission WHERE code = 'settings:manage';

DROP TABLE IF EXISTS invoice_logo;
DROP TABLE IF EXISTS invoice_setting;

DELETE FROM flyway_schema_history WHERE version = '035';
