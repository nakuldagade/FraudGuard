-- Phase 11 tuning (see docs/performance.md). The dashboard counts and filters on (status, review_state) together:
-- "REVIEW and still unreviewed". With only a status index PostgreSQL had to read every REVIEW row and filter it.
-- The composite index answers both the page total and the summary counts from the index alone.
CREATE INDEX idx_transactions_status_review ON transactions (status, review_state);
-- The old status-only index is now redundant (the composite starts with status), so drop it to keep writes cheaper.
DROP INDEX idx_transactions_status;
