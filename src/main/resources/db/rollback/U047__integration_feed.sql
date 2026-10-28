-- Annulation de V047 : flux d'événements d'intégration (événements et état du flux supprimés avec les tables).

DROP TABLE IF EXISTS integration_feed_state;
DROP TABLE IF EXISTS integration_outbox;

DELETE FROM flyway_schema_history WHERE version = '047';
