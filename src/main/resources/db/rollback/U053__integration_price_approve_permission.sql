-- Annulation de V053 : permission integration:price-approve (retirée de tous les rôles).
-- Pour les tests de retour arrière : jamais en production (aucune suppression de données, plan § 36).

DELETE FROM role_permission WHERE permission_id IN (SELECT id FROM permission WHERE code = 'integration:price-approve');
DELETE FROM permission WHERE code = 'integration:price-approve';

DELETE FROM flyway_schema_history WHERE version = '053';
