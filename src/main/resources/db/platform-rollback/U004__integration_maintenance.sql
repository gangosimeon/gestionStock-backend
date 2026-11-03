-- Annulation de V004 : maintenance de l'intégration boutique (bail et état par entreprise).
-- Sans perte de donnée métier : le bail et l'ordre de passage sont recréés vides par la ré-application de V004
-- (prochain passage : génération 1, toutes les entreprises considérées comme jamais visitées). Aucun passage de
-- maintenance ne doit être en cours.

DROP TABLE IF EXISTS platform.integration_maintenance_company;
DROP TABLE IF EXISTS platform.integration_maintenance_lease;

DELETE FROM platform.flyway_schema_history WHERE version = '004';
