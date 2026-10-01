-- Events waiting to be sent to Kafka. A row is written in the SAME database transaction
-- as the business change it describes, so the two can never get out of sync.
CREATE TABLE outbox_events (
                               id            UUID          PRIMARY KEY,   -- same value as the event's eventId
                               aggregate_id  UUID          NOT NULL,      -- the transaction this event is about
                               topic         VARCHAR(128)  NOT NULL,      -- Kafka topic to publish to
                               message_key   VARCHAR(128)  NOT NULL,      -- Kafka message key (the accountId)
                               payload       VARCHAR(4000) NOT NULL,      -- the event as JSON text
                               created_at    TIMESTAMPTZ   NOT NULL,
                               published_at  TIMESTAMPTZ                  -- NULL = still waiting to be sent
);

-- The relay only ever looks at unsent rows, so index just those
CREATE INDEX idx_outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_aggregate ON outbox_events (aggregate_id);