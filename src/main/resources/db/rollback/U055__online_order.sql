-- Annulation de V055 : commandes en ligne, lignes, réservations et lignes de réservation.
-- Pour les tests de retour arrière : jamais en production.

DROP TABLE IF EXISTS online_reservation_line;
DROP TABLE IF EXISTS online_reservation;
DROP TABLE IF EXISTS online_order_line;
DROP TABLE IF EXISTS online_order;

DELETE FROM flyway_schema_history WHERE version = '055';
