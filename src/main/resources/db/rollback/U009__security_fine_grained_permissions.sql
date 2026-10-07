-- Annule V009__security_fine_grained_permissions.sql : regroupe les permissions fines
-- dans les anciennes (un rôle détenant l'une des nouvelles récupère l'ancienne), supprime
-- role:manage. Les rôles personnalisés créés depuis V009 conservent leurs autres droits.
INSERT INTO permission (code, description) VALUES
    ('product:write',     'Créer et modifier les produits'),
    ('category:write',    'Créer et modifier les catégories'),
    ('brand:write',       'Créer et modifier les marques'),
    ('warehouse:write',   'Créer et modifier les dépôts'),
    ('supplier:write',    'Créer et modifier les fournisseurs'),
    ('customer:write',    'Créer et modifier les clients'),
    ('purchase:write',    'Créer et modifier les commandes fournisseurs'),
    ('cash:read-all',     'Consulter l''historique de toutes les caisses'),
    ('inventory:write',   'Saisir des inventaires'),
    ('expense:write',     'Enregistrer des dépenses'),
    ('appointment:write', 'Gérer les rendez-vous');

WITH mapping (new_code, old_code) AS (VALUES
    ('product:create', 'product:write'), ('product:update', 'product:write'),
    ('supplier:create', 'supplier:write'), ('supplier:update', 'supplier:write'),
    ('customer:create', 'customer:write'), ('customer:update', 'customer:write'),
    ('category:manage', 'category:write'), ('brand:manage', 'brand:write'),
    ('warehouse:manage', 'warehouse:write'), ('purchase:create', 'purchase:write'),
    ('cash:read', 'cash:read-all'), ('inventory:create', 'inventory:write'),
    ('expense:create', 'expense:write'), ('appointment:manage', 'appointment:write'))
INSERT INTO role_permission (role_id, permission_id)
SELECT DISTINCT rp.role_id, old_permission.id
FROM mapping m
JOIN permission new_permission ON new_permission.code = m.new_code
JOIN role_permission rp ON rp.permission_id = new_permission.id
JOIN permission old_permission ON old_permission.code = m.old_code
ON CONFLICT DO NOTHING;

DELETE FROM role_permission WHERE permission_id IN (SELECT id FROM permission WHERE code IN (
    'product:create', 'product:update', 'category:manage', 'brand:manage', 'warehouse:manage', 'supplier:create',
    'supplier:update', 'customer:create', 'customer:update', 'purchase:create', 'cash:read', 'inventory:create',
    'expense:create', 'appointment:manage', 'role:manage'));
DELETE FROM permission WHERE code IN (
    'product:create', 'product:update', 'category:manage', 'brand:manage', 'warehouse:manage', 'supplier:create',
    'supplier:update', 'customer:create', 'customer:update', 'purchase:create', 'cash:read', 'inventory:create',
    'expense:create', 'appointment:manage', 'role:manage');

COMMENT ON COLUMN role.built_in IS 'Rôle système livré par les migrations : non supprimable.';
DELETE FROM flyway_schema_history WHERE version = '009';
