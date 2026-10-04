-- Analyst review workflow (Phase 8).
-- The model's decision (transactions.status) is never changed by an analyst. The analyst's verdict
-- is stored separately: review_state is the CURRENT verdict, analyst_reviews keeps the full history.
ALTER TABLE transactions ADD COLUMN review_state VARCHAR(24) NOT NULL DEFAULT 'UNREVIEWED';

CREATE TABLE analyst_reviews (
                                 id             UUID         PRIMARY KEY,
                                 transaction_id UUID         NOT NULL REFERENCES transactions (id),
                                 action         VARCHAR(32)  NOT NULL,     -- CONFIRM_FRAUD / MARK_LEGITIMATE / REQUEST_FURTHER_REVIEW
                                 note           VARCHAR(500),
                                 reviewer       VARCHAR(64)  NOT NULL,
                                 created_at     TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_analyst_reviews_transaction ON analyst_reviews (transaction_id, created_at DESC);

-- Speed up the dashboard's list, filters and sorting
CREATE INDEX idx_transactions_review_state ON transactions (review_state);
CREATE INDEX idx_transactions_occurred_at ON transactions (occurred_at DESC);
CREATE INDEX idx_transactions_risk_score ON transactions (risk_score);