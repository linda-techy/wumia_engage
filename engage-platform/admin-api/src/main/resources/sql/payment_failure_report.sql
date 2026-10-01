-- The Phase 0 spike report, as the console shows it: failed payments per IST
-- day and match method. Last 30 days. No parameters.
SELECT ist_day::date AS ist_day, match_method, failures, without_mobile, avg_webhook_lag_s
  FROM payment_failure_match_report
 WHERE ist_day >= (now() AT TIME ZONE 'Asia/Kolkata')::date - 30
