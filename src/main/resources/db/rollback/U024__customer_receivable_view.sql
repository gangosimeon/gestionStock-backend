-- Retour arrière de V024 (vue des créances clients).

DROP VIEW IF EXISTS v_customer_receivable;

DELETE FROM flyway_schema_history WHERE version = '024';
