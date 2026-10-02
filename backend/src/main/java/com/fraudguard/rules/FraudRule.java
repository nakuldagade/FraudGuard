package com.fraudguard.rules;

import com.fraudguard.dto.RuleHit;
import com.fraudguard.dto.TransactionEvent;
import com.fraudguard.dto.VelocityFeatures;
import java.util.Optional;

// One fraud rule. A rule only looks at numbers it is given: it knows nothing about Kafka,
// Redis, the database or HTTP. That keeps rules simple to test and easy to add.
public interface FraudRule {

    // Reason code stored with the transaction, e.g. HIGH_VELOCITY
    String code();

    // Returns a hit (with score and explanation) if the rule fires, or empty if it doesn't
    Optional<RuleHit> evaluate(TransactionEvent.Payload transaction, VelocityFeatures features);
}