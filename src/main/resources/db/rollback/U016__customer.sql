-- Annule V016__customer.sql — DÉTRUIT LES CLIENTS ET LEURS COMPTES.
-- Prérequis : aucune table des ventes ou paiements ne référence encore customer.
DELETE FROM role_permission
WHERE permission_id = (SELECT id FROM permission WHERE code = 'customer:credit');
DELETE FROM permission WHERE code = 'customer:credit';
UPDATE permission SET description = 'Modifier les clients et leur plafond de crédit' WHERE code = 'customer:update';

DROP TABLE customer_account_entry;
DROP TABLE customer;
DROP SEQUENCE seq_customer_code;
DELETE FROM flyway_schema_history WHERE version = '016';
