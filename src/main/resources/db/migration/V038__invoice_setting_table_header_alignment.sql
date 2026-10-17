-- =============================================================================
-- V038 — Configuration de la facture : alignement des en-têtes du tableau des produits
--
-- Intitulés des colonnes (Désignation, Quantité, Prix unitaire, Total…) des factures et des
-- devis : AUTO (textes à gauche, nombres à droite), LEFT, CENTER (par défaut) ou RIGHT.
-- La configuration existante passe à CENTER.
--
-- Annulation : db/rollback/U038__invoice_setting_table_header_alignment.sql
-- =============================================================================

ALTER TABLE invoice_setting ADD COLUMN table_header_alignment varchar(10) NOT NULL DEFAULT 'CENTER';

ALTER TABLE invoice_setting ADD CONSTRAINT ck_invoice_setting_table_header_alignment
    CHECK (table_header_alignment IN ('AUTO', 'LEFT', 'CENTER', 'RIGHT'));

COMMENT ON COLUMN invoice_setting.table_header_alignment IS
    'Alignement des intitulés de colonnes du tableau des produits (AUTO : textes à gauche, nombres à droite).';
