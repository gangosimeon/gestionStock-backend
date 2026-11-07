-- =============================================================================
-- V056 — Index sur product_id pour les FK de online_order_line et online_reservation_line
--
-- La convention du projet exige un index couvrant la colonne de tête de chaque clé étrangère
-- (SchemaConventionsIT.everyForeignKeyIsCoveredByAnIndex). Ces deux index manquaient dans V055.
-- =============================================================================

CREATE INDEX ix_online_order_line_product ON online_order_line (product_id);

CREATE INDEX ix_online_reservation_line_product ON online_reservation_line (product_id);
