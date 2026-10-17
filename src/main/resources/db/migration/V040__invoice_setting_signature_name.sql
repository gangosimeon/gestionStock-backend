-- =============================================================================
-- V040 — Configuration de la facture : nom complet du signataire
--
-- Bloc de signature : titre (signature_text, ex. « Le responsable »), espace pour la signature
-- et le cachet, puis nom complet du signataire en gras (facultatif : vide = non affiché).
--
-- Annulation : db/rollback/U040__invoice_setting_signature_name.sql
-- =============================================================================

ALTER TABLE invoice_setting ADD COLUMN signature_name varchar(150) NOT NULL DEFAULT '';

COMMENT ON COLUMN invoice_setting.signature_name IS
    'Nom complet du signataire, sous l''espace réservé à la signature et au cachet (vide : non affiché).';
