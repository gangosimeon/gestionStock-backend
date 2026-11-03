-- Annulation de V052 : acquittements du flux d'intégration par liaison.
-- Destructive, sans danger (v17-D15) : après annulation (et retour au code d'avant G17), la purge ne voit aucun
-- acquittement ; avec le code de G17, une liaison active ou suspendue sans acquittement bloque la purge.

DROP TABLE IF EXISTS integration_link_ack;

DELETE FROM flyway_schema_history WHERE version = '052';
