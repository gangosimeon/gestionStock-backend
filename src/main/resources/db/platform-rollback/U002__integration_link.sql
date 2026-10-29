-- Annulation de V002 (plateforme) : registre des liaisons avec les boutiques en ligne (v14-D9).
-- DESTRUCTIF : clients, demandes, liaisons, codes et compteurs sont supprimés. Les secrets de liaison
-- détenus par JeCréeMaBoutique deviennent inutilisables ; toute liaison devra être autorisée à nouveau.
-- Ordre inverse des dépendances. Historique Flyway de la plateforme : platform.flyway_schema_history.

DROP TABLE IF EXISTS platform.integration_rate_limit;
DROP TABLE IF EXISTS platform.integration_authorization_code;
DROP TABLE IF EXISTS platform.integration_link;
DROP TABLE IF EXISTS platform.integration_authorization_request;
DROP TABLE IF EXISTS platform.integration_client_redirect_uri;
DROP TABLE IF EXISTS platform.integration_client;

DELETE FROM platform.flyway_schema_history WHERE version = '002';
