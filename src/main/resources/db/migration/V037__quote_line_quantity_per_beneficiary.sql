-- =============================================================================
-- V037 — Devis : quantité par bénéficiaire
--
-- Une ligne de devis peut être chiffrée « par bénéficiaire » : quantité totale = quantité par
-- bénéficiaire × nombre de bénéficiaires du devis, calculée par le serveur. C'est cette
-- quantité totale (quote_line.quantity) qui est multipliée par le prix unitaire et, une fois
-- le devis facturé à la caisse, sortie du stock par la vente. Sans quantité par bénéficiaire,
-- la quantité est saisie directement (comportement de V036).
--
-- Annulation : db/rollback/U037__quote_line_quantity_per_beneficiary.sql
-- =============================================================================

ALTER TABLE quote_line ADD COLUMN quantity_per_beneficiary integer;

ALTER TABLE quote_line ADD CONSTRAINT ck_quote_line_quantity_per_beneficiary
    CHECK (quantity_per_beneficiary IS NULL OR quantity_per_beneficiary > 0);

COMMENT ON COLUMN quote_line.quantity_per_beneficiary IS
    'Quantité pour un bénéficiaire (facultative) ; quantity = quantity_per_beneficiary × quote.beneficiaries.';
