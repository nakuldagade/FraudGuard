-- Removes the rows created by perf/seed-100k.sql (and the load tests' own rows, whose account ids start 'load-').
DELETE FROM analyst_reviews WHERE transaction_id IN (SELECT id FROM transactions WHERE idempotency_key LIKE 'perf-%' OR account_id LIKE 'load-%');
DELETE FROM outbox_events   WHERE aggregate_id  IN (SELECT id FROM transactions WHERE idempotency_key LIKE 'perf-%' OR account_id LIKE 'load-%');
DELETE FROM transactions WHERE idempotency_key LIKE 'perf-%' OR account_id LIKE 'load-%';
VACUUM ANALYZE transactions;
