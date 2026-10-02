-- Result of the rule engine for each transaction (Phase 5).
-- Both columns stay NULL until the consumer has processed the transaction.
ALTER TABLE transactions ADD COLUMN risk_score INTEGER;       -- 0-100
ALTER TABLE transactions ADD COLUMN rule_hits VARCHAR(4000);  -- JSON list of {code, score, reason}