-- Annule V003__security_reference_data.sql : retire les rôles système et le catalogue
-- de permissions (et leur matrice). Prérequis : aucun utilisateur ne porte ces rôles (U005).
DELETE FROM role_permission;
DELETE FROM permission;
DELETE FROM role WHERE built_in;
DELETE FROM flyway_schema_history WHERE version = '003';
