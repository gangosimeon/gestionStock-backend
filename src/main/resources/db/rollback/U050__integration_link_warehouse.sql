-- Annulation de V050 : dépôts vendables par liaison.
-- DESTRUCTIF : les dépôts choisis pour chaque liaison sont perdus. Après annulation (et retour au code d'avant
-- G4a), le périmètre redevient celui du lot 1 (tous les dépôts actifs). Après une ré-application de V050, chaque
-- liaison est sans dépôt, donc sans disponibilité, jusqu'à sa réactivation avec une liste reconfirmée.

DROP TABLE IF EXISTS integration_link_warehouse;

DELETE FROM flyway_schema_history WHERE version = '050';
