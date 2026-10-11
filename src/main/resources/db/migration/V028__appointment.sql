-- =============================================================================
-- V028 — Rendez-vous clients.
--
-- Règles (docs/appointments.md) :
--   * un rendez-vous appartient à un dépôt ; il vise un client du fichier (customer_id, nom
--     recopié) ou un prospect (nom et téléphone saisis). Le téléphone est toujours renseigné ;
--   * statuts : SCHEDULED (planifié) → COMPLETED (honoré) | NO_SHOW (absent) | CANCELLED
--     (annulé, motif obligatoire). Un rendez-vous clos ne se modifie plus ;
--   * un seul rendez-vous planifié par dépôt et par horaire (uk_appointment_slot) ;
--   * pas de suppression : un rendez-vous s'annule, son historique reste.
--
-- Annulation : db/rollback/U028__appointment.sql
-- =============================================================================

CREATE TABLE appointment (
    id                  uuid          NOT NULL DEFAULT gen_random_uuid(),
    warehouse_id        uuid          NOT NULL,
    customer_id         uuid,
    customer_name       varchar(200)  NOT NULL,
    phone               varchar(30)   NOT NULL,
    scheduled_at        timestamptz   NOT NULL,
    status              varchar(20)   NOT NULL DEFAULT 'SCHEDULED',
    note                varchar(1000),
    closed_at           timestamptz,
    closed_by           uuid,
    cancellation_reason varchar(500),
    version             bigint        NOT NULL DEFAULT 0,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    created_by          uuid,
    updated_at          timestamptz   NOT NULL DEFAULT now(),
    updated_by          uuid,
    CONSTRAINT pk_appointment PRIMARY KEY (id),
    CONSTRAINT fk_appointment_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse (id) ON DELETE RESTRICT,
    CONSTRAINT fk_appointment_customer FOREIGN KEY (customer_id) REFERENCES customer (id) ON DELETE RESTRICT,
    CONSTRAINT ck_appointment_customer_name_not_blank CHECK (btrim(customer_name) <> ''),
    CONSTRAINT ck_appointment_phone_format CHECK (phone ~ '^[0-9+() .-]+$' AND phone ~ '[0-9]'),
    CONSTRAINT ck_appointment_status CHECK (status IN ('SCHEDULED', 'COMPLETED', 'NO_SHOW', 'CANCELLED')),
    CONSTRAINT ck_appointment_closure CHECK ((status = 'SCHEDULED') = (closed_at IS NULL)),
    CONSTRAINT ck_appointment_cancellation CHECK ((status = 'CANCELLED') = (cancellation_reason IS NOT NULL))
);

-- Un créneau = un rendez-vous planifié par dépôt (un rendez-vous clos libère son créneau).
CREATE UNIQUE INDEX uk_appointment_slot ON appointment (warehouse_id, scheduled_at) WHERE status = 'SCHEDULED';
CREATE INDEX ix_appointment_warehouse_id_scheduled_at ON appointment (warehouse_id, scheduled_at);
CREATE INDEX ix_appointment_customer_id ON appointment (customer_id, scheduled_at DESC);

COMMENT ON TABLE appointment IS 'Rendez-vous clients d''un dépôt ; annulés plutôt que supprimés.';
COMMENT ON COLUMN appointment.customer_id IS 'Client du fichier, ou NULL pour un prospect.';
COMMENT ON COLUMN appointment.customer_name IS 'Nom affiché : celui du client à la prise de rendez-vous, ou du prospect.';
COMMENT ON COLUMN appointment.closed_at IS 'Passage à un statut final (honoré, absent ou annulé).';

CREATE TRIGGER trg_appointment_prevent_delete
    BEFORE DELETE ON appointment
    FOR EACH ROW EXECUTE FUNCTION fn_prevent_modification();
CREATE TRIGGER trg_appointment_prevent_truncate
    BEFORE TRUNCATE ON appointment
    FOR EACH STATEMENT EXECUTE FUNCTION fn_prevent_modification();

CREATE FUNCTION fn_appointment_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status <> 'SCHEDULED' THEN
        RAISE EXCEPTION 'appointment % : un rendez-vous clos (%) ne se modifie plus.', OLD.id, OLD.status
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.warehouse_id <> OLD.warehouse_id
       OR NEW.created_at <> OLD.created_at
       OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
        RAISE EXCEPTION 'appointment % : dépôt et auteur figés.', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION fn_appointment_guard() IS 'Trigger de appointment : rendez-vous clos figé, dépôt et auteur figés.';

CREATE TRIGGER trg_appointment_guard
    BEFORE UPDATE ON appointment
    FOR EACH ROW EXECUTE FUNCTION fn_appointment_guard();
