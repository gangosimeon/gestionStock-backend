-- Annule V008__security_refresh_token.sql — ferme toutes les sessions en cours.
ALTER TABLE app_user DROP COLUMN credentials_changed_at;
DROP TABLE refresh_token;
DELETE FROM flyway_schema_history WHERE version = '008';
