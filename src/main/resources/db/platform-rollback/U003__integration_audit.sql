-- Annulation de V003 : audit de référence des liaisons boutique.
-- DESTRUCTIF : les traces de référence sont perdues ; les copies déjà écrites dans le journal de chaque
-- entreprise (audit_log) sont conservées. À jouer avant U002.

DROP TABLE IF EXISTS platform.integration_audit_log;

DELETE FROM platform.flyway_schema_history WHERE version = '003';
