-- Annulation de V049 : permission integration:manage (retirée de tous les rôles).

DELETE FROM role_permission WHERE permission_id IN (SELECT id FROM permission WHERE code = 'integration:manage');
DELETE FROM permission WHERE code = 'integration:manage';

DELETE FROM flyway_schema_history WHERE version = '049';
