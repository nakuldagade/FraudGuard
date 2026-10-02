package com.fraudguard.rules;

import com.fraudguard.dto.RuleHit;
import com.fraudguard.dto.TransactionEvent;
import com.fraudguard.dto.VelocityFeatures;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// An amount far above what this account normally spends.
@Component
@RequiredArgsConstructor
public class UnusualAmountRule implements FraudRule {

    public static final String CODE = "UNUSUAL_AMOUNT";

    private final RuleProperties properties;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Optional<RuleHit> evaluate(TransactionEvent.Payload transaction, VelocityFeatures features) {
        RuleProperties.UnusualAmount config = properties.getUnusualAmount();
        if (!config.isEnabled()) {
            return Optional.empty();
        }
        // No z-score means the account has too little history to judge: never guess
        Double zScore = features.amountZScore();
        if (zScore == null) {
            return Optional.empty();
        }
        // Small amounts are ignored however unusual they look (avoids noisy false alarms)
        if (transaction.amount().compareTo(config.getMinAmount()) < 0) {
            return Optional.empty();
        }
        if (zScore >= config.getMinZScore()) {
            return Optional.of(new RuleHit(CODE, config.getScore(), String.format(Locale.ROOT,
                    "Amount %s is %.1f standard deviations above this account's average of %s",
                    transaction.amount().toPlainString(), zScore, features.averageAmount().toPlainString())));
        }
        return Optional.empty();
    }
}