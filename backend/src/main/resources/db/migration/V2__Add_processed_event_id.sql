-- ID of the last Kafka event applied to this transaction.
-- If the same event is delivered twice, the consumer sees a match and skips it.
ALTER TABLE transactions ADD COLUMN processed_event_id UUID;