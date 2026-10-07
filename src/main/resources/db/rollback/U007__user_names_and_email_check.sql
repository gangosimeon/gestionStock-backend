-- Annule V007__user_names_and_email_check.sql : reconstitue full_name à partir du
-- prénom et du nom, puis supprime les colonnes séparées.
ALTER TABLE app_user ADD COLUMN full_name varchar(150);
UPDATE app_user SET full_name = btrim(coalesce(first_name || ' ', '') || last_name);
DROP INDEX ix_app_user_last_name_first_name;
ALTER TABLE app_user
    ALTER COLUMN full_name SET NOT NULL,
    DROP CONSTRAINT ck_app_user_email_format,
    DROP CONSTRAINT ck_app_user_first_name_not_blank,
    DROP CONSTRAINT ck_app_user_last_name_not_blank,
    DROP COLUMN first_name,
    DROP COLUMN last_name,
    ADD CONSTRAINT ck_app_user_full_name_not_blank CHECK (btrim(full_name) <> '');
DELETE FROM flyway_schema_history WHERE version = '007';
