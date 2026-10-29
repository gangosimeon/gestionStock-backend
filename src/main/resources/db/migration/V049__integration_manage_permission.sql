-- =============================================================================
-- Permission de gestion des liaisons avec les boutiques en ligne (intégration JeCréeMaBoutique,
-- lot 2, décision v14-D7) : autoriser, suspendre, réactiver et révoquer une liaison.
-- Attribuée au rôle ADMIN uniquement ; GESTIONNAIRE et CAISSIER inchangés.
-- =============================================================================

INSERT INTO permission (code, description) VALUES
    ('integration:manage', 'Gérer les liaisons avec les boutiques en ligne (autoriser, suspendre, révoquer)');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r CROSS JOIN permission p
WHERE r.code = 'ADMIN' AND p.code = 'integration:manage';
