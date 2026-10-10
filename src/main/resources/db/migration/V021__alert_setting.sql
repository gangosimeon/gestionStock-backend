-- =============================================================================================
-- V021 : paramètres des alertes.
--
-- Les alertes (rupture, seuil minimum, stock faible, surstock, stock immobilisé, commandes en
-- retard, créances échues, caisses, inventaires) sont CALCULÉES à la demande à partir des
-- données de référence : aucune table d'alertes, rien à synchroniser. Seuls leurs paramètres
-- sont stockés ici (une seule ligne), modifiables sans redémarrage par la permission
-- alert:configure. Le seuil minimum propre à chaque produit reste product.alert_threshold.
-- =============================================================================================

CREATE TABLE alert_setting (
    id                            uuid          NOT NULL DEFAULT gen_random_uuid(),
    singleton                     boolean       NOT NULL DEFAULT true,
    default_min_stock             integer       NOT NULL DEFAULT 0,
    low_stock_margin_percent      integer       NOT NULL DEFAULT 50,
    dormant_stock_days            integer       NOT NULL DEFAULT 90,
    purchase_overdue_grace_days   integer       NOT NULL DEFAULT 0,
    default_payment_terms_days    integer       NOT NULL DEFAULT 30,
    credit_limit_warning_percent  integer       NOT NULL DEFAULT 90,
    cash_session_max_open_hours   integer       NOT NULL DEFAULT 24,
    cash_difference_tolerance     numeric(14,2) NOT NULL DEFAULT 1000,
    cash_difference_lookback_days integer       NOT NULL DEFAULT 7,
    version                       bigint        NOT NULL DEFAULT 0,
    created_at                    timestamptz   NOT NULL DEFAULT now(),
    created_by                    uuid,
    updated_at                    timestamptz   NOT NULL DEFAULT now(),
    updated_by                    uuid,
    CONSTRAINT pk_alert_setting PRIMARY KEY (id),
    CONSTRAINT ck_alert_setting_singleton CHECK (singleton),
    CONSTRAINT ck_alert_setting_default_min_stock CHECK (default_min_stock BETWEEN 0 AND 100000),
    CONSTRAINT ck_alert_setting_low_stock_margin CHECK (low_stock_margin_percent BETWEEN 0 AND 1000),
    CONSTRAINT ck_alert_setting_dormant_days CHECK (dormant_stock_days BETWEEN 0 AND 3650),
    CONSTRAINT ck_alert_setting_purchase_grace CHECK (purchase_overdue_grace_days BETWEEN 0 AND 365),
    CONSTRAINT ck_alert_setting_payment_terms CHECK (default_payment_terms_days BETWEEN 0 AND 365),
    CONSTRAINT ck_alert_setting_credit_warning CHECK (credit_limit_warning_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_alert_setting_cash_open_hours CHECK (cash_session_max_open_hours BETWEEN 0 AND 720),
    CONSTRAINT ck_alert_setting_cash_tolerance CHECK (cash_difference_tolerance >= 0),
    CONSTRAINT ck_alert_setting_cash_lookback CHECK (cash_difference_lookback_days BETWEEN 0 AND 365)
);

CREATE UNIQUE INDEX uk_alert_setting_singleton ON alert_setting (singleton);

COMMENT ON TABLE alert_setting IS 'Paramètres des alertes calculées (une seule ligne). 0 désactive l''alerte concernée.';
COMMENT ON COLUMN alert_setting.default_min_stock IS 'Seuil minimum appliqué aux produits sans seuil propre (product.alert_threshold = 0) ; 0 = aucun.';
COMMENT ON COLUMN alert_setting.low_stock_margin_percent IS 'Stock faible : au-dessus du seuil minimum mais à moins de ce pourcentage du seuil ; 0 = désactivé.';
COMMENT ON COLUMN alert_setting.dormant_stock_days IS 'Stock dormant : aucune sortie depuis ce nombre de jours ; 0 = désactivé.';
COMMENT ON COLUMN alert_setting.purchase_overdue_grace_days IS 'Commande en retard : jours de tolérance après la date de livraison prévue.';
COMMENT ON COLUMN alert_setting.default_payment_terms_days IS 'Délai de paiement des clients sans délai propre (customer.payment_terms_days = 0).';
COMMENT ON COLUMN alert_setting.credit_limit_warning_percent IS 'Plafond de crédit : alerte à partir de ce pourcentage utilisé ; 0 = désactivé.';
COMMENT ON COLUMN alert_setting.cash_session_max_open_hours IS 'Caisse ouverte depuis plus de ce nombre d''heures ; 0 = désactivé.';
COMMENT ON COLUMN alert_setting.cash_difference_tolerance IS 'Écart de clôture toléré (valeur absolue, en FCFA).';
COMMENT ON COLUMN alert_setting.cash_difference_lookback_days IS 'Écarts de caisse signalés sur les clôtures de ce nombre de jours ; 0 = désactivé.';

INSERT INTO alert_setting DEFAULT VALUES;

-- --- Permission : paramétrer les alertes ------------------------------------------------------

INSERT INTO permission (code, description)
VALUES ('alert:configure', 'Paramétrer les seuils des alertes');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r CROSS JOIN permission p
WHERE r.code IN ('ADMIN', 'GESTIONNAIRE') AND p.code = 'alert:configure';

-- --- Index de lecture des alertes ------------------------------------------------------------

-- Commandes fournisseurs ouvertes, par date prévue (retards).
CREATE INDEX ix_purchase_order_open_expected ON purchase_order (expected_delivery_date)
    WHERE status IN ('ORDERED', 'PARTIALLY_RECEIVED');
