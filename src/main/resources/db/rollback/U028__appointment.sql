-- Annule V028__appointment.sql — DÉTRUIT LES RENDEZ-VOUS ENREGISTRÉS.
DROP TRIGGER trg_appointment_guard ON appointment;
DROP FUNCTION fn_appointment_guard();
DROP TABLE appointment;
DELETE FROM flyway_schema_history WHERE version = '028';
