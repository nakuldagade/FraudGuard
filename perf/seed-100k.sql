-- Adds N synthetic transactions for read-path performance tests. SYNTHETIC: random data, not realistic fraud.
-- Marked by idempotency_key 'perf-%' so perf/cleanup.sql removes exactly these rows. No outbox rows are created,
-- so the Kafka pipeline never sees them.
--   docker compose exec -T postgres psql -U fraud_user -d fraud -v rows=100000 -f - < perf/seed-100k.sql
\if :{?rows} \else \set rows 100000 \endif

INSERT INTO transactions (id, idempotency_key, request_hash, account_id, merchant_id, transaction_type, amount,
                          currency, device_id, country, occurred_at, status, created_at, updated_at,
                          risk_score, rule_hits, anomaly_score, model_version, policy_version, reason_codes,
                          decision_explanation, decision_inputs, review_state)
SELECT gen_random_uuid(),
       'perf-' || g.n,
       md5(g.n::text) || md5((g.n + 1)::text),
       'perf-acc-' || (g.n % 2000),                                   -- 2000 accounts, ~50 rows each
       'm-' || (1 + g.n % 8),
       'PAYMENT',
       round((random() * 5000 + 1)::numeric, 2),
       'INR',
       'dev-' || (g.n % 3000),
       'IN',
       now() - (random() * interval '90 days'),
       st.status,
       now(), now(),
       CASE st.status WHEN 'BLOCKED' THEN 80 + (random() * 20)::int WHEN 'REVIEW' THEN 40 + (random() * 39)::int ELSE (random() * 39)::int END,
       '[]',
       random(),
       CASE WHEN g.n % 10 = 0 THEN NULL ELSE 'gbm-perf' END,
       'policy-v1',
       CASE st.status WHEN 'APPROVED' THEN 'NO_RISK_SIGNALS' ELSE 'HIGH_VELOCITY' END,
       'Synthetic performance-test row.',
       repeat('{"synthetic":true}', 80),                              -- about 1.4 KB, like a real snapshot
       CASE WHEN st.status <> 'APPROVED' AND random() < 0.3 THEN 'CONFIRMED_FRAUD' ELSE 'UNREVIEWED' END
FROM (SELECT n, random() AS r FROM generate_series(1, :rows) AS n) g           -- one random draw per row
CROSS JOIN LATERAL (SELECT CASE WHEN g.r < 0.90 THEN 'APPROVED' WHEN g.r < 0.965 THEN 'REVIEW' ELSE 'BLOCKED' END AS status) st;

ANALYZE transactions;
SELECT status, count(*) FROM transactions WHERE idempotency_key LIKE 'perf-%' GROUP BY 1 ORDER BY 1;
