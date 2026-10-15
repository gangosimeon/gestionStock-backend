-- Retour arrière de V029 : rend au gestionnaire la vue de tous les dépôts et leur administration.

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r CROSS JOIN permission p
WHERE r.code = 'GESTIONNAIRE' AND p.code IN ('warehouse:all', 'warehouse:manage')
ON CONFLICT DO NOTHING;

DELETE FROM flyway_schema_history WHERE version = '029';
