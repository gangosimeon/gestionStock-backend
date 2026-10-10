-- Retour arrière de V021 (paramètres des alertes).

DROP INDEX IF EXISTS ix_purchase_order_open_expected;

DELETE FROM role_permission
WHERE permission_id = (SELECT id FROM permission WHERE code = 'alert:configure');
DELETE FROM permission WHERE code = 'alert:configure';

DROP TABLE IF EXISTS alert_setting;
