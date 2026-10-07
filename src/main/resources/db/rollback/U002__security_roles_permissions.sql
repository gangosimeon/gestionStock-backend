-- Annule V002__security_roles_permissions.sql.
DROP TABLE role_permission;
DROP TABLE permission;
DROP TABLE role;
DELETE FROM flyway_schema_history WHERE version = '002';
