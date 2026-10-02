package com.fraudguard.rules;

import com.fraudguard.dto.RuleHit;
import com.fraudguard.dto.TransactionEvent;
import com.fraudguard.dto.VelocityFeatures;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// Too many transactions from one account in a short time.
@Component
@RequiredArgsConstructor
public class HighVelocityRule implements FraudRule {

    public static final String CODE = "HIGH_VELOCITY";

    private final RuleProperties properties;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Optional<RuleHit> evaluate(TransactionEvent.Payload transaction, VelocityFeatures features) {
        RuleProperties.HighVelocity config = properties.getHighVelocity();
        if (!config.isEnabled()) {
            return Optional.empty();
        }
        // "More than" the limit: exactly at the limit is still allowed
        if (features.transactionCount5m() > config.getMaxPerFiveMinutes()) {
            return Optional.of(new RuleHit(CODE, config.getScore(),
                    features.transactionCount5m() + " transactions in the last 5 minutes (limit "
                            + config.getMaxPerFiveMinutes() + ")"));
        }
        if (features.transactionCount1h() > config.getMaxPerHour()) {
            return Optional.of(new RuleHit(CODE, config.getScore(),
                    features.transactionCount1h() + " transactions in the last hour (limit "
                            + config.getMaxPerHour() + ")"));
        }
        return Optional.empty();
    }
}