-- =============================================================================
-- Permission d'approbation des prix proposés par les boutiques en ligne (intégration JeCréeMaBoutique,
-- lot 3, étape G21, décision v14-D7) : approuver ou refuser une proposition de prix d'une boutique liée.
-- Attribuée au rôle ADMIN uniquement ; GESTIONNAIRE et CAISSIER inchangés (le gestionnaire peut la recevoir
-- par la gestion des rôles). Additive : insertions seulement.
-- =============================================================================

INSERT INTO permission (code, description) VALUES
    ('integration:price-approve', 'Approuver ou refuser les prix proposés par les boutiques en ligne');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r CROSS JOIN permission p
WHERE r.code = 'ADMIN' AND p.code = 'integration:price-approve';
