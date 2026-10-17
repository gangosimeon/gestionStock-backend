-- =============================================================================
-- V039 — Configuration de la facture : police des factures et des devis
--
-- MODEL (par défaut) : police du modèle choisi ; TIMES_NEW_ROMAN ou ARIAL pour l'imposer à tout
-- le document (écran, impression, PDF).
--
-- Annulation : db/rollback/U039__invoice_setting_font.sql
-- =============================================================================

ALTER TABLE invoice_setting ADD COLUMN font_family varchar(20) NOT NULL DEFAULT 'MODEL';

ALTER TABLE invoice_setting ADD CONSTRAINT ck_invoice_setting_font_family
    CHECK (font_family IN ('MODEL', 'TIMES_NEW_ROMAN', 'ARIAL'));

COMMENT ON COLUMN invoice_setting.font_family IS
    'Police des factures et des devis : MODEL (celle du modèle), TIMES_NEW_ROMAN ou ARIAL.';
