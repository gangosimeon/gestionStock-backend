-- =============================================================================
-- V001 — Socle : fonctions partagées
--
-- fn_prevent_modification() : attachée par trigger aux tables journal (audit_log,
-- puis stock_movement et grands livres). Toute tentative de modification, de
-- suppression ou de vidage est refusée : ces tables sont en ajout seul.
--
-- Conventions du schéma : backend/docs/persistence.md
-- Annulation : db/rollback/U001__core_functions.sql
-- =============================================================================

CREATE FUNCTION fn_prevent_modification() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'La table % est en ajout seul : % interdit.', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

COMMENT ON FUNCTION fn_prevent_modification() IS
    'Trigger des tables journal (ajout seul) : refuse UPDATE, DELETE et TRUNCATE.';
