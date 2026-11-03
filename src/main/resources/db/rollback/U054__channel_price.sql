-- Annulation de V054 : prix proposés par les boutiques, verrou des propositions, réponses idempotentes.
-- Pour les tests de retour arrière : jamais en production (aucune suppression de données, plan § 36).

DROP TABLE IF EXISTS integration_idempotency;
DROP TABLE IF EXISTS channel_price_head;
DROP TABLE IF EXISTS channel_price;

DELETE FROM flyway_schema_history WHERE version = '054';
