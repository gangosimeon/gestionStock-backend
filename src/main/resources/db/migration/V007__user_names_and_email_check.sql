-- =============================================================================
-- V007 — Utilisateurs : nom et prénom séparés, contrôle du format d'email
--
-- full_name (V005) est remplacé par last_name (obligatoire) et first_name
-- (facultatif). Les lignes existantes sont découpées au premier espace :
-- « Pierre Diallo » → first_name = Pierre, last_name = Diallo ; un nom sans espace
-- devient last_name. Le nom complet est recalculé par l'application (fullName dans
-- l'API, contrat du front inchangé).
--
-- Annulation : db/rollback/U007__user_names_and_email_check.sql
-- =============================================================================

ALTER TABLE app_user
    ADD COLUMN first_name varchar(75),
    ADD COLUMN last_name  varchar(75);

UPDATE app_user
SET first_name = CASE WHEN position(' ' IN btrim(full_name)) > 0
                      THEN split_part(btrim(full_name), ' ', 1) END,
    last_name  = CASE WHEN position(' ' IN btrim(full_name)) > 0
                      THEN btrim(substr(btrim(full_name), position(' ' IN btrim(full_name)) + 1))
                      ELSE btrim(full_name) END;

ALTER TABLE app_user
    ALTER COLUMN last_name SET NOT NULL,
    DROP CONSTRAINT ck_app_user_full_name_not_blank,
    DROP COLUMN full_name,
    ADD CONSTRAINT ck_app_user_last_name_not_blank CHECK (btrim(last_name) <> ''),
    ADD CONSTRAINT ck_app_user_first_name_not_blank CHECK (first_name IS NULL OR btrim(first_name) <> ''),
    ADD CONSTRAINT ck_app_user_email_format CHECK (email IS NULL OR email ~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$');

-- Tri et recherche par nom dans la liste des utilisateurs.
CREATE INDEX ix_app_user_last_name_first_name ON app_user (lower(last_name), lower(first_name));

COMMENT ON COLUMN app_user.last_name IS 'Nom de famille (obligatoire).';
COMMENT ON COLUMN app_user.first_name IS 'Prénom (facultatif).';
