-- Annulation de V051 : drapeau de publication du flux d'intégration.
-- Après annulation (et retour au code d'avant G13), la publication ne dépend plus que de l'interrupteur global
-- geststock.integration.publish.enabled.

DROP TABLE IF EXISTS integration_publication;

DELETE FROM flyway_schema_history WHERE version = '051';
