-- =============================================================================================
-- V024 : créances clients (vue v_customer_receivable).
--
-- Une ligne par client : solde dû (débits − crédits du relevé) et part échue. Règlements, avoirs
-- et annulations sont imputés aux plus anciennes créances (premier entré, premier soldé) ; une
-- créance est échue quand sa date + le délai de paiement du client est dépassée (délai par
-- défaut des paramètres d'alerte si le client n'en a pas). Partagée par les alertes
-- (créances échues) et le rapport clients : une seule définition de la règle.
-- =============================================================================================

CREATE VIEW v_customer_receivable AS
WITH debits AS (
    SELECT e.customer_id, e.amount, e.created_at,
           sum(e.amount) OVER (PARTITION BY e.customer_id ORDER BY e.created_at, e.id) AS cumulated
    FROM customer_account_entry e
    WHERE e.direction = 'DEBIT'),
credits AS (
    SELECT customer_id, sum(amount) AS total
    FROM customer_account_entry
    WHERE direction = 'CREDIT'
    GROUP BY customer_id),
terms AS (
    SELECT c.id AS customer_id,
           CASE WHEN c.payment_terms_days > 0 THEN c.payment_terms_days
                ELSE coalesce((SELECT s.default_payment_terms_days FROM alert_setting s LIMIT 1), 30) END AS days
    FROM customer c)
SELECT t.customer_id,
       t.days AS payment_terms_days,
       coalesce(sum(d.amount), 0) - coalesce(cr.total, 0) AS balance,
       coalesce(sum(greatest(0, least(d.amount, d.cumulated - coalesce(cr.total, 0))))
                FILTER (WHERE d.created_at + make_interval(days => t.days) < now()), 0) AS overdue_amount,
       min(d.created_at + make_interval(days => t.days))
           FILTER (WHERE d.cumulated > coalesce(cr.total, 0)
                     AND d.created_at + make_interval(days => t.days) < now()) AS oldest_due_at
FROM terms t
LEFT JOIN debits d ON d.customer_id = t.customer_id
LEFT JOIN credits cr ON cr.customer_id = t.customer_id
GROUP BY t.customer_id, t.days, cr.total;

COMMENT ON VIEW v_customer_receivable IS 'Créances par client : solde dû, part échue (FIFO), plus ancienne échéance dépassée.';
