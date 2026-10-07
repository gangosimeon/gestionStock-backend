-- =============================================================================
-- V009 — RBAC : permissions plus fines et gestion des rôles
--
-- Les permissions « write » regroupant création et modification sont scindées là où
-- les deux droits ont un sens métier distinct (produits, fournisseurs, clients). Les
-- autres sont renommées selon l'action réelle (manage, create, read). Ajout de
-- role:manage (gestion des rôles et de leurs permissions).
--
-- Report de la matrice : chaque rôle qui détenait une ancienne permission reçoit
-- toutes les permissions qui la remplacent. Aucun droit n'est perdu ni ajouté, sauf
-- role:manage accordé à ADMIN.
--
--   product:write     → product:create + product:update
--   supplier:write    → supplier:create + supplier:update
--   customer:write    → customer:create + customer:update
--   category:write    → category:manage        brand:write       → brand:manage
--   warehouse:write   → warehouse:manage       purchase:write    → purchase:create
--   cash:read-all     → cash:read              inventory:write   → inventory:create
--   expense:write     → expense:create         appointment:write → appointment:manage
--
-- Annulation : db/rollback/U009__security_fine_grained_permissions.sql
-- =============================================================================

INSERT INTO permission (code, description) VALUES
    ('product:create',     'Créer des produits'),
    ('product:update',     'Modifier les produits et leurs tarifs'),
    ('category:manage',    'Créer, modifier et désactiver les catégories'),
    ('brand:manage',       'Créer, modifier et désactiver les marques'),
    ('warehouse:manage',   'Créer, modifier et désactiver les dépôts'),
    ('supplier:create',    'Créer des fournisseurs'),
    ('supplier:update',    'Modifier les fournisseurs'),
    ('customer:create',    'Créer des clients'),
    ('customer:update',    'Modifier les clients et leur plafond de crédit'),
    ('purchase:create',    'Créer des commandes fournisseurs'),
    ('cash:read',          'Consulter l''historique de toutes les sessions de caisse'),
    ('inventory:create',   'Saisir des inventaires'),
    ('expense:create',     'Enregistrer des dépenses'),
    ('appointment:manage', 'Créer, modifier et supprimer des rendez-vous'),
    ('role:manage',        'Gérer les rôles et leurs permissions');

WITH mapping (old_code, new_code) AS (VALUES
    ('product:write',     'product:create'),
    ('product:write',     'product:update'),
    ('supplier:write',    'supplier:create'),
    ('supplier:write',    'supplier:update'),
    ('customer:write',    'customer:create'),
    ('customer:write',    'customer:update'),
    ('category:write',    'category:manage'),
    ('brand:write',       'brand:manage'),
    ('warehouse:write',   'warehouse:manage'),
    ('purchase:write',    'purchase:create'),
    ('cash:read-all',     'cash:read'),
    ('inventory:write',   'inventory:create'),
    ('expense:write',     'expense:create'),
    ('appointment:write', 'appointment:manage'))
INSERT INTO role_permission (role_id, permission_id)
SELECT DISTINCT rp.role_id, new_permission.id
FROM mapping m
JOIN permission old_permission ON old_permission.code = m.old_code
JOIN role_permission rp ON rp.permission_id = old_permission.id
JOIN permission new_permission ON new_permission.code = m.new_code
ON CONFLICT DO NOTHING;

DELETE FROM role_permission
WHERE permission_id IN (SELECT id FROM permission WHERE code IN (
    'product:write', 'supplier:write', 'customer:write', 'category:write', 'brand:write', 'warehouse:write',
    'purchase:write', 'cash:read-all', 'inventory:write', 'expense:write', 'appointment:write'));

DELETE FROM permission WHERE code IN (
    'product:write', 'supplier:write', 'customer:write', 'category:write', 'brand:write', 'warehouse:write',
    'purchase:write', 'cash:read-all', 'inventory:write', 'expense:write', 'appointment:write');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r CROSS JOIN permission p
WHERE r.code = 'ADMIN' AND p.code = 'role:manage';

COMMENT ON COLUMN role.built_in IS
    'Rôle livré par les migrations : non supprimable. ADMIN est en plus non modifiable (toutes les permissions).';
