-- V35: measurement (P7-T03). Lift of each journey against its holdout, what
-- messaging cost per intent, and the monthly check of our WhatsApp spend
-- against Meta's bill.

-- Net revenue per bucket per IST week, with the bucket size as it stood at the
-- end of that week. The denominator counts identities with no orders, which is
-- what makes treated vs holdout an honest comparison.
CREATE VIEW holdout_lift_weekly AS
WITH rev AS (
  SELECT h.experiment, h.bucket,
         date_trunc('week', o.created_at AT TIME ZONE 'Asia/Kolkata')::date AS week_ist,
         sum(o.total_paise - o.refunded_paise)                            AS net_revenue_paise
    FROM holdouts h
    JOIN orders o ON o.identity_id = h.identity_id
                 AND o.created_at >= h.assigned_at
                 AND o.cancelled_at IS NULL
   GROUP BY 1, 2, 3
)
SELECT r.experiment, r.week_ist, r.bucket, r.net_revenue_paise,
       (SELECT count(*) FROM holdouts h2
         WHERE h2.experiment = r.experiment AND h2.bucket = r.bucket
           AND h2.assigned_at < ((r.week_ist + 7)::timestamp AT TIME ZONE 'Asia/Kolkata')) AS identities
  FROM rev r;

CREATE VIEW spend_by_intent_daily AS
SELECT (s.created_at AT TIME ZONE 'Asia/Kolkata')::date AS day_ist,
       split_part(s.intent_key, ':', 1)                   AS intent,
       s.channel, s.category,
       count(*) FILTER (WHERE s.status NOT IN ('blocked','deferred')) AS sends,
       coalesce(sum(s.cost_paise), 0)                     AS cost_paise
  FROM sends s
 GROUP BY 1, 2, 3, 4;

-- The lift report reads one experiment's assignments at a time.
CREATE INDEX holdouts_experiment_idx ON holdouts (experiment, bucket, assigned_at);

-- Meta's WhatsApp bill for a month, by category, as an operator uploads it.
CREATE TABLE meta_billing (
  month        DATE NOT NULL CHECK (extract(day FROM month) = 1),
  category     msg_category NOT NULL,
  amount_paise BIGINT NOT NULL CHECK (amount_paise >= 0),
  uploaded_by  UUID REFERENCES operators(id),
  uploaded_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (month, category)
);

-- The monthly comparison (worker, 1st of the month 06:00 IST).
CREATE TABLE spend_reconciliations (
  month          DATE NOT NULL,
  category       msg_category NOT NULL,
  ledger_paise   BIGINT NOT NULL,
  provider_paise BIGINT NOT NULL,
  diff_pct       NUMERIC(8, 2),
  checked_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (month, category)
);
