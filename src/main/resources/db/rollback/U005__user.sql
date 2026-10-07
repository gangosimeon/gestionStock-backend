-- Annule V005__user.sql — DÉTRUIT LES COMPTES UTILISATEURS.
DROP TABLE user_warehouse;
DROP TABLE user_role;
DROP TABLE app_user;
DELETE FROM flyway_schema_history WHERE version = '005';
