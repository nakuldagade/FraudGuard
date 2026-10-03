-- The final decision (Phase 7): which policy produced it, why, and everything it was based on.
ALTER TABLE transactions ADD COLUMN policy_version VARCHAR(32);           -- e.g. policy-v1
ALTER TABLE transactions ADD COLUMN reason_codes VARCHAR(500);            -- comma-separated, e.g. HIGH_VELOCITY,ML_ANOMALY_VERY_HIGH
ALTER TABLE transactions ADD COLUMN decision_explanation VARCHAR(1000);   -- plain-English summary
ALTER TABLE transactions ADD COLUMN decision_inputs VARCHAR(8000);        -- JSON snapshot of every input, for later analysis