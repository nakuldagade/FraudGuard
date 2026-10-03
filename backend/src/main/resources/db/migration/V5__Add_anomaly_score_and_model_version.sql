-- Result of the ML service for each transaction (Phase 6).
-- Both columns stay NULL when the ML service was unavailable for that transaction.
ALTER TABLE transactions ADD COLUMN anomaly_score DOUBLE PRECISION;   -- higher = more unusual (not a probability)
ALTER TABLE transactions ADD COLUMN model_version VARCHAR(64);        -- e.g. isoforest-v1