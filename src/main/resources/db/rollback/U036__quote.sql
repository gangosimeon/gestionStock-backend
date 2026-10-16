-- Annulation de V036 (devis). Supprime les devis enregistrés : à n'exécuter qu'en connaissance de cause.

DELETE FROM role_permission
WHERE permission_id IN (SELECT id FROM permission WHERE code IN ('quote:read', 'quote:manage'));
DELETE FROM permission WHERE code IN ('quote:read', 'quote:manage');

DROP TABLE IF EXISTS quote_line;
DROP TABLE IF EXISTS quote;
DROP SEQUENCE IF EXISTS seq_quote_number;

DELETE FROM flyway_schema_history WHERE version = '036';
