-- Annulation de V045 : liens d'accès (les liens non utilisés deviennent inutilisables) et permission
-- company:manage (retirée de tous les rôles).

DELETE FROM role_permission WHERE permission_id IN (SELECT id FROM permission WHERE code = 'company:manage');
DELETE FROM permission WHERE code = 'company:manage';

DROP TABLE IF EXISTS account_access_token;

DELETE FROM flyway_schema_history WHERE version = '045';
