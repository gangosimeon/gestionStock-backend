-- =============================================================================
-- Données de démonstration (profil dev uniquement) : dépôts.
-- Migration répétable, idempotente : rejouée par Flyway si ce fichier change.
-- Reprend le second dépôt du mock front (wh_2 « Dépôt secondaire »).
-- =============================================================================

INSERT INTO warehouse (code, name, type, address)
VALUES ('DEP-01', 'Dépôt secondaire', 'WAREHOUSE', 'Zone industrielle, Abidjan')
ON CONFLICT (code) DO NOTHING;
