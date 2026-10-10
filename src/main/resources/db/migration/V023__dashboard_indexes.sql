-- =============================================================================================
-- V023 : index de lecture du tableau de bord.
--
-- Les indicateurs (chiffre d'affaires, marge, achats, évolution du stock) agrègent des pièces
-- sur une période, pour un dépôt ou pour tous. Les index existants commencent par le dépôt ;
-- ceux-ci servent la vue « tous les dépôts » et, pour les ventes, permettent un parcours
-- d'index seul (colonnes agrégées incluses, ventes annulées exclues).
-- =============================================================================================

-- Ventes validées d'une période : total et coût lus dans l'index.
CREATE INDEX ix_sale_completed_created_at ON sale (created_at)
    INCLUDE (warehouse_id, total, cost_total)
    WHERE status = 'COMPLETED';

-- Mouvements d'une période, tous dépôts (évolution du stock).
CREATE INDEX ix_stock_movement_created_at ON stock_movement (created_at)
    INCLUDE (warehouse_id, quantity);

-- Retours et réceptions d'une période, tous dépôts.
CREATE INDEX ix_customer_return_created_at ON customer_return (created_at);
CREATE INDEX ix_goods_receipt_received_at ON goods_receipt (received_at);
