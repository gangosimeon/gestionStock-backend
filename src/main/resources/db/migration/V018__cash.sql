-- =============================================================================
-- V018 — Caisse : sessions individuelles et écritures de caisse
--
-- Remplace la « caisse globale » du backend simulé (une seule session pour tout le monde,
-- ventes rattachées par fenêtre horaire) par une traçabilité individuelle :
--
-- cash_session : la caisse d'UN caissier dans UN dépôt (poste facultatif). Au plus une
-- session ouverte par utilisateur. Fond de caisse à l'ouverture ; à la clôture, montant
-- compté, montant attendu et écart sont figés.
--
-- cash_entry : chaque mouvement d'argent, en ajout seul, avec un numéro (référence
-- OPC-AAAA-nnnnnn), un type, un sens, un moyen de paiement, un montant, sa session, son
-- auteur et sa date. Types :
--   IN  : SALE (encaissement d'une vente), CUSTOMER_PAYMENT (règlement de dette),
--         CASH_IN (apport manuel)
--   OUT : SALE_REFUND (remboursement d'une vente annulée), CASH_OUT (sortie manuelle)
-- Espèces attendues = fond + entrées en espèces − sorties en espèces.
--
-- Garde-fous en base :
--   * fn_cash_entry_guard : écriture seulement dans une session OUVERTE, et uniquement par
--     son titulaire ; opérations manuelles en espèces ;
--   * fn_cash_session_guard : une session clôturée est figée ; fond, caissier, dépôt immuables ;
--   * fn_sale_cash_consistency (DIFFÉRÉE, au COMMIT) : une vente payée a son encaissement du
--     même montant et du même moyen ; une vente payée puis annulée a son remboursement ;
--   * fn_customer_payment_consistency (DIFFÉRÉE, au COMMIT) : un règlement au compte client a
--     son encaissement en caisse, et inversement (même pièce, même client, même montant).
--
-- Annulation : db/rollback/U018__cash.sql
-- =============================================================================

CREATE SEQUENCE seq_cash_session_number;
CREATE SEQUENCE seq_cash_entry_number;

CREATE TABLE cash_session (
    id              uuid          NOT NULL DEFAULT gen_random_uuid(),
    number          varchar(30)   NOT NULL,
    warehouse_id    uuid          NOT NULL,
    cashier_id      uuid          NOT NULL,
    terminal        varchar(50),
    status          varchar(10)   NOT NULL DEFAULT 'OPEN',
    opening_balance numeric(14,2) NOT NULL,
    opened_at       timestamptz   NOT NULL,
    opening_note    varchar(500),
    closed_at       timestamptz,
    closed_by       uuid,
    counted_cash    numeric(14,2),
    expected_cash   numeric(14,2),
    difference      numeric(14,2),
    closing_note    varchar(500),
    version         bigint        NOT NULL DEFAULT 0,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    created_by      uuid,
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    updated_by      uuid,
    CONSTRAINT pk_cash_session PRIMARY KEY (id),
    CONSTRAINT fk_cash_session_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse (id) ON DELETE RESTRICT,
    CONSTRAINT fk_cash_session_cashier FOREIGN KEY (cashier_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_cash_session_closed_by FOREIGN KEY (closed_by) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_cash_session_status CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT ck_cash_session_opening_balance CHECK (opening_balance >= 0),
    CONSTRAINT ck_cash_session_closing CHECK (
        (status = 'OPEN' AND closed_at IS NULL AND closed_by IS NULL AND counted_cash IS NULL
             AND expected_cash IS NULL AND difference IS NULL)
        OR (status = 'CLOSED' AND closed_at IS NOT NULL AND closed_by IS NOT NULL AND counted_cash >= 0
             AND expected_cash IS NOT NULL AND difference = counted_cash - expected_cash))
);

CREATE UNIQUE INDEX uk_cash_session_number ON cash_session (number);
-- Au plus une caisse ouverte par utilisateur (pas de caisse partagée sans titulaire).
CREATE UNIQUE INDEX uk_cash_session_open_cashier ON cash_session (cashier_id) WHERE status = 'OPEN';
CREATE INDEX ix_cash_session_cashier_id ON cash_session (cashier_id, opened_at DESC);
CREATE INDEX ix_cash_session_warehouse_id_opened_at ON cash_session (warehouse_id, opened_at DESC);
CREATE INDEX ix_cash_session_closed_by ON cash_session (closed_by);

COMMENT ON TABLE cash_session IS 'Sessions de caisse : la caisse d''un caissier dans un dépôt, de l''ouverture à la clôture.';
COMMENT ON COLUMN cash_session.number IS 'Numéro lisible CAI-AAAA-nnnnnn (séquence seq_cash_session_number).';
COMMENT ON COLUMN cash_session.terminal IS 'Poste ou tiroir utilisé (facultatif, ex. « Caisse 1 »).';
COMMENT ON COLUMN cash_session.expected_cash IS 'Espèces attendues figées à la clôture : fond + entrées − sorties en espèces.';
COMMENT ON COLUMN cash_session.difference IS 'Écart de caisse figé à la clôture : compté − attendu.';

CREATE TABLE cash_entry (
    id               uuid          NOT NULL DEFAULT gen_random_uuid(),
    number           varchar(30)   NOT NULL,
    session_id       uuid          NOT NULL,
    entry_type       varchar(30)   NOT NULL,
    direction        varchar(3)    NOT NULL,
    payment_method   varchar(20)   NOT NULL,
    amount           numeric(14,2) NOT NULL,
    customer_id      uuid,
    source_type      varchar(30),
    source_id        uuid,
    source_reference varchar(100),
    note             varchar(500),
    created_at       timestamptz   NOT NULL,
    created_by       uuid          NOT NULL,
    CONSTRAINT pk_cash_entry PRIMARY KEY (id),
    CONSTRAINT fk_cash_entry_session FOREIGN KEY (session_id) REFERENCES cash_session (id) ON DELETE RESTRICT,
    CONSTRAINT fk_cash_entry_customer FOREIGN KEY (customer_id) REFERENCES customer (id) ON DELETE RESTRICT,
    CONSTRAINT fk_cash_entry_created_by FOREIGN KEY (created_by) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_cash_entry_type CHECK (entry_type IN ('SALE', 'CUSTOMER_PAYMENT', 'CASH_IN', 'SALE_REFUND', 'CASH_OUT')),
    CONSTRAINT ck_cash_entry_direction CHECK (
        (entry_type IN ('SALE', 'CUSTOMER_PAYMENT', 'CASH_IN') AND direction = 'IN')
        OR (entry_type IN ('SALE_REFUND', 'CASH_OUT') AND direction = 'OUT')),
    CONSTRAINT ck_cash_entry_payment_method CHECK (payment_method IN ('CASH', 'MOBILE_MONEY', 'BANK_TRANSFER')),
    -- Apports et sorties manuels : des espèces.
    CONSTRAINT ck_cash_entry_manual_cash CHECK (entry_type NOT IN ('CASH_IN', 'CASH_OUT') OR payment_method = 'CASH'),
    CONSTRAINT ck_cash_entry_amount CHECK (amount > 0),
    -- Un règlement de dette désigne son client ; une écriture de vente désigne sa vente.
    CONSTRAINT ck_cash_entry_customer_payment CHECK (entry_type <> 'CUSTOMER_PAYMENT' OR customer_id IS NOT NULL),
    CONSTRAINT ck_cash_entry_source CHECK (entry_type NOT IN ('SALE', 'SALE_REFUND', 'CUSTOMER_PAYMENT')
                                           OR (source_type IS NOT NULL AND source_id IS NOT NULL))
);

CREATE UNIQUE INDEX uk_cash_entry_number ON cash_entry (number);
-- Une pièce n'est encaissée (ou remboursée) qu'une fois.
CREATE UNIQUE INDEX uk_cash_entry_source ON cash_entry (source_type, source_id, entry_type) WHERE source_id IS NOT NULL;
CREATE INDEX ix_cash_entry_session_id ON cash_entry (session_id, created_at);
CREATE INDEX ix_cash_entry_customer_id ON cash_entry (customer_id, created_at DESC);
CREATE INDEX ix_cash_entry_created_by ON cash_entry (created_by);

COMMENT ON TABLE cash_entry IS 'Écritures de caisse (encaissements et décaissements), en ajout seul.';
COMMENT ON COLUMN cash_entry.number IS 'Référence de l''opération OPC-AAAA-nnnnnn (séquence seq_cash_entry_number).';
COMMENT ON COLUMN cash_entry.source_reference IS 'Pièce d''origine lisible (numéro de vente…).';

CREATE TRIGGER trg_cash_entry_prevent_modification
    BEFORE UPDATE OR DELETE ON cash_entry
    FOR EACH ROW EXECUTE FUNCTION fn_prevent_modification();
CREATE TRIGGER trg_cash_entry_prevent_truncate
    BEFORE TRUNCATE ON cash_entry
    FOR EACH STATEMENT EXECUTE FUNCTION fn_prevent_modification();
CREATE TRIGGER trg_cash_session_prevent_delete
    BEFORE DELETE ON cash_session
    FOR EACH ROW EXECUTE FUNCTION fn_prevent_modification();
CREATE TRIGGER trg_cash_session_prevent_truncate
    BEFORE TRUNCATE ON cash_session
    FOR EACH STATEMENT EXECUTE FUNCTION fn_prevent_modification();

-- --- Une écriture : session ouverte, titulaire de la session ----------------------------------

CREATE FUNCTION fn_cash_entry_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    s cash_session%ROWTYPE;
BEGIN
    SELECT * INTO s FROM cash_session WHERE id = NEW.session_id;
    IF s.id IS NULL OR s.status <> 'OPEN' THEN
        RAISE EXCEPTION 'cash_entry : la caisse % n''est pas ouverte.', coalesce(s.number, NEW.session_id::text)
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.created_by <> s.cashier_id THEN
        RAISE EXCEPTION 'cash_entry : seul le titulaire de la caisse % y enregistre des opérations.', s.number
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION fn_cash_entry_guard() IS 'Trigger de cash_entry : session ouverte et opération du titulaire uniquement.';

CREATE TRIGGER trg_cash_entry_guard
    BEFORE INSERT ON cash_entry
    FOR EACH ROW EXECUTE FUNCTION fn_cash_entry_guard();

-- --- Une session clôturée est figée --------------------------------------------------------------

CREATE FUNCTION fn_cash_session_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'CLOSED' THEN
        RAISE EXCEPTION 'cash_session % : une caisse clôturée ne se modifie plus.', OLD.number
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.number <> OLD.number OR NEW.warehouse_id <> OLD.warehouse_id OR NEW.cashier_id <> OLD.cashier_id
       OR NEW.opening_balance <> OLD.opening_balance OR NEW.opened_at <> OLD.opened_at THEN
        RAISE EXCEPTION 'cash_session % : caissier, dépôt, fond et ouverture sont immuables.', OLD.number
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION fn_cash_session_guard() IS 'Trigger de cash_session : identité figée, session clôturée immuable.';

CREATE TRIGGER trg_cash_session_guard
    BEFORE UPDATE ON cash_session
    FOR EACH ROW EXECUTE FUNCTION fn_cash_session_guard();

-- --- Vente payée ⇔ encaissement (vérifié au COMMIT) -------------------------------------------------

CREATE FUNCTION fn_sale_cash_consistency() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    s sale%ROWTYPE;
BEGIN
    SELECT * INTO s FROM sale WHERE id = NEW.id;
    IF s.paid_amount > 0 AND NOT EXISTS (
            SELECT 1 FROM cash_entry e
            WHERE e.source_type = 'SALE' AND e.source_id = s.id AND e.entry_type = 'SALE'
              AND e.amount = s.paid_amount AND e.payment_method = s.payment_method) THEN
        RAISE EXCEPTION 'sale % : montant payé sans encaissement en caisse.', s.number
            USING ERRCODE = 'check_violation';
    END IF;
    IF s.status = 'CANCELLED' AND s.paid_amount > 0 AND NOT EXISTS (
            SELECT 1 FROM cash_entry e
            WHERE e.source_type = 'SALE' AND e.source_id = s.id AND e.entry_type = 'SALE_REFUND'
              AND e.amount = s.paid_amount AND e.payment_method = s.payment_method) THEN
        RAISE EXCEPTION 'sale % : vente payée annulée sans remboursement en caisse.', s.number
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

COMMENT ON FUNCTION fn_sale_cash_consistency() IS
    'Contrainte différée : vente payée ⇒ encaissement ; vente payée annulée ⇒ remboursement.';

CREATE CONSTRAINT TRIGGER trg_sale_cash_consistency
    AFTER INSERT OR UPDATE OF status ON sale
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION fn_sale_cash_consistency();

-- --- Règlement client ⇔ encaissement (vérifié au COMMIT) -------------------------------------------

CREATE FUNCTION fn_customer_payment_consistency() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_TABLE_NAME = 'cash_entry' THEN
        IF NEW.entry_type = 'CUSTOMER_PAYMENT' AND NOT EXISTS (
                SELECT 1 FROM customer_account_entry a
                WHERE a.entry_type = 'PAYMENT' AND a.source_type = 'CUSTOMER_PAYMENT' AND a.source_id = NEW.source_id
                  AND a.customer_id = NEW.customer_id AND a.amount = NEW.amount) THEN
            RAISE EXCEPTION 'cash_entry % : encaissement d''un règlement sans écriture au compte du client.', NEW.number
                USING ERRCODE = 'check_violation';
        END IF;
    ELSE
        IF NEW.entry_type = 'PAYMENT' AND NOT EXISTS (
                SELECT 1 FROM cash_entry c
                WHERE c.entry_type = 'CUSTOMER_PAYMENT' AND c.source_type = 'CUSTOMER_PAYMENT'
                  AND c.source_id = NEW.source_id AND c.customer_id = NEW.customer_id AND c.amount = NEW.amount) THEN
            RAISE EXCEPTION 'customer_account_entry : règlement client sans encaissement en caisse.'
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

COMMENT ON FUNCTION fn_customer_payment_consistency() IS
    'Contrainte différée : règlement au compte client ⇔ encaissement en caisse.';

CREATE CONSTRAINT TRIGGER trg_cash_entry_customer_payment_consistency
    AFTER INSERT ON cash_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION fn_customer_payment_consistency();

CREATE CONSTRAINT TRIGGER trg_customer_account_entry_payment_consistency
    AFTER INSERT ON customer_account_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION fn_customer_payment_consistency();
