-- =============================================================================
-- Création des bases locales GestStock (développement et tests).
-- Idempotent : peut être relancé sans erreur ni perte de données.
--
-- À exécuter avec un superutilisateur PostgreSQL (ex. postgres) :
--
--   psql -h localhost -p 5432 -U postgres \
--        -v app_password='MOT_DE_PASSE_CHOISI' \
--        -f scripts/create-local-db.sql
--
-- Crée :
--   - le rôle applicatif geststock (non superutilisateur) ;
--   - la base geststock      (profil dev : schéma + données de démonstration) ;
--   - la base geststock_test (tests d'intégration sans Docker).
-- Les tables sont créées ensuite par Flyway au démarrage de l'application.
-- =============================================================================

\set ON_ERROR_STOP on

SELECT format('CREATE ROLE geststock LOGIN PASSWORD %L', :'app_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'geststock')
\gexec

-- Le mot de passe est aligné même si le rôle existait déjà.
SELECT format('ALTER ROLE geststock WITH LOGIN PASSWORD %L', :'app_password')
\gexec

SELECT 'CREATE DATABASE geststock OWNER geststock ENCODING ''UTF8'' TEMPLATE template0'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'geststock')
\gexec

SELECT 'CREATE DATABASE geststock_test OWNER geststock ENCODING ''UTF8'' TEMPLATE template0'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'geststock_test')
\gexec

-- Fuseau horaire de session par défaut : UTC (les colonnes timestamptz restent correctes
-- quel que soit le fuseau, mais l'affichage psql devient cohérent avec l'API).
ALTER DATABASE geststock SET timezone TO 'UTC';
ALTER DATABASE geststock_test SET timezone TO 'UTC';

-- Depuis PostgreSQL 15, le schéma public n'est plus ouvert à tous : on le confie au
-- propriétaire de chaque base, qui y crée ses tables via Flyway.
\connect geststock
ALTER SCHEMA public OWNER TO geststock;

\connect geststock_test
ALTER SCHEMA public OWNER TO geststock;
