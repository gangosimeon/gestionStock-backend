-- =============================================================================
-- V003 — Sécurité : données de référence (indispensables en production)
--
-- 3 rôles système et catalogue des permissions, conformes à la matrice de
-- l'architecture cible (architecture-backend-geststock.md, §17.2).
-- Écarts volontaires avec l'ancien mock front :
--   - stock:adjust est refusé au CAISSIER ;
--   - les rendez-vous sont soumis à permission.
--
-- Annulation : db/rollback/U003__security_reference_data.sql
-- =============================================================================

INSERT INTO role (code, label, description, built_in) VALUES
    ('ADMIN',        'Administrateur', 'Accès complet, gestion des utilisateurs et du journal d''audit', true),
    ('GESTIONNAIRE', 'Gestionnaire',   'Catalogue, stock, achats, inventaires, rapports',               true),
    ('CAISSIER',     'Caissier',       'Ventes, caisse, clients et rendez-vous',                         true);

INSERT INTO permission (code, description) VALUES
    -- Lecture des référentiels
    ('product:read',       'Consulter les produits'),
    ('category:read',      'Consulter les catégories'),
    ('brand:read',         'Consulter les marques'),
    ('warehouse:read',     'Consulter les dépôts'),
    ('customer:read',      'Consulter les clients'),
    ('stock:read',         'Consulter les niveaux et mouvements de stock'),
    ('supplier:read',      'Consulter les fournisseurs'),
    -- Catalogue
    ('product:write',      'Créer et modifier les produits'),
    ('product:delete',     'Désactiver ou supprimer des produits'),
    ('category:write',     'Créer et modifier les catégories'),
    ('brand:write',        'Créer et modifier les marques'),
    -- Dépôts et stock
    ('warehouse:write',    'Créer et modifier les dépôts'),
    ('warehouse:all',      'Vue consolidée de tous les dépôts'),
    ('stock:adjust',       'Saisir des mouvements de stock manuels'),
    ('stock:transfer',     'Transférer du stock entre dépôts'),
    -- Fournisseurs et achats
    ('supplier:write',     'Créer et modifier les fournisseurs'),
    ('supplier:delete',    'Désactiver ou supprimer des fournisseurs'),
    ('purchase:read',      'Consulter les commandes fournisseurs'),
    ('purchase:write',     'Créer et modifier les commandes fournisseurs'),
    ('purchase:receive',   'Réceptionner les commandes fournisseurs'),
    ('purchase:pay',       'Enregistrer les paiements fournisseurs'),
    -- Clients et ventes
    ('customer:write',     'Créer et modifier les clients'),
    ('customer:delete',    'Désactiver ou supprimer des clients'),
    ('customer:pay',       'Encaisser les paiements clients'),
    ('sale:read',          'Consulter les ventes'),
    ('sale:create',        'Enregistrer des ventes'),
    ('sale:cancel',        'Annuler des ventes'),
    ('return:create',      'Enregistrer des retours'),
    -- Caisse
    ('cash:operate',       'Ouvrir, alimenter et clôturer la caisse'),
    ('cash:read-all',      'Consulter l''historique de toutes les caisses'),
    -- Inventaire
    ('inventory:read',     'Consulter les inventaires'),
    ('inventory:write',    'Saisir des inventaires'),
    ('inventory:validate', 'Valider des inventaires'),
    -- Dépenses
    ('expense:read',       'Consulter les dépenses'),
    ('expense:write',      'Enregistrer des dépenses'),
    ('expense:delete',     'Supprimer des dépenses'),
    -- Rendez-vous
    ('appointment:read',   'Consulter les rendez-vous'),
    ('appointment:write',  'Gérer les rendez-vous'),
    -- Pilotage et administration
    ('report:read',        'Consulter le tableau de bord et les rapports'),
    ('user:manage',        'Gérer les utilisateurs'),
    ('audit:read',         'Consulter le journal d''audit');

-- ADMIN : toutes les permissions.
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r CROSS JOIN permission p
WHERE r.code = 'ADMIN';

-- GESTIONNAIRE : tout sauf suppressions sensibles, utilisateurs et audit.
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r CROSS JOIN permission p
WHERE r.code = 'GESTIONNAIRE'
  AND p.code NOT IN ('product:delete', 'supplier:delete', 'customer:delete', 'expense:delete',
                     'user:manage', 'audit:read');

-- CAISSIER : vente, caisse, clients, rendez-vous et lecture des référentiels.
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r CROSS JOIN permission p
WHERE r.code = 'CAISSIER'
  AND p.code IN ('product:read', 'category:read', 'brand:read', 'warehouse:read', 'customer:read',
                 'stock:read', 'customer:write', 'customer:pay', 'sale:read', 'sale:create',
                 'cash:operate', 'appointment:read', 'appointment:write');
