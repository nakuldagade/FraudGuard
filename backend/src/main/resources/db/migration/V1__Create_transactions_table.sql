CREATE TABLE transactions (
                              id               UUID          PRIMARY KEY,
    -- Client-supplied key that makes retries safe (used in the service, Part 2)
                              idempotency_key  VARCHAR(128)  NOT NULL,
    -- SHA-256 fingerprint of the request body; detects key reuse with different data
                              request_hash     VARCHAR(64)   NOT NULL,
                              account_id       VARCHAR(64)   NOT NULL,
                              merchant_id      VARCHAR(64),                  -- optional (deposits/transfers may have none)
                              transaction_type VARCHAR(16)   NOT NULL,       -- DEPOSIT / WITHDRAWAL / TRANSFER / PAYMENT
                              amount           NUMERIC(14,2) NOT NULL CHECK (amount > 0),
                              currency         VARCHAR(3)    NOT NULL,
                              device_id        VARCHAR(64),                  -- optional
                              country          VARCHAR(2),                   -- optional
                              occurred_at      TIMESTAMPTZ   NOT NULL,       -- when the transaction happened
                              status           VARCHAR(16)   NOT NULL,       -- PENDING / APPROVED / REVIEW / BLOCKED
                              created_at       TIMESTAMPTZ   NOT NULL,
                              updated_at       TIMESTAMPTZ   NOT NULL,
    -- The database itself rejects two rows with the same key, even if requests race
                              CONSTRAINT uq_transactions_idempotency_key UNIQUE (idempotency_key)
);

-- Speeds up "recent transactions for an account" (needed for velocity features later)
CREATE INDEX idx_transactions_account_occurred ON transactions (account_id, occurred_at DESC);
-- Speeds up filtering by status (analyst dashboard later)
CREATE INDEX idx_transactions_status ON transactions (status);