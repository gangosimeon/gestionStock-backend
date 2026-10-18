-- =============================================================================
-- V042 — Configuration de la facture : montant reçu et monnaie rendue
--
-- Affiche, sur la facture d'une vente payée en espèces, le montant reçu du client et la
-- monnaie rendue (s'ils ont été saisis à la caisse). Activé par défaut.
--
-- Annulation : db/rollback/U042__invoice_setting_cash_tender.sql
-- =============================================================================

ALTER TABLE invoice_setting ADD COLUMN show_cash_tender boolean NOT NULL DEFAULT true;

COMMENT ON COLUMN invoice_setting.show_cash_tender IS
    'Afficher sur la facture le montant reçu du client et la monnaie rendue (ventes en espèces).';
