package com.fraudguard.rules;

import com.fraudguard.dto.RuleHit;
import com.fraudguard.dto.TransactionEvent;
import com.fraudguard.dto.VelocityFeatures;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// A device this account has never used, combined with a large amount compared with its average.
@Component
@RequiredArgsConstructor
public class NewDeviceHighAmountRule implements FraudRule {

    public static final String CODE = "NEW_DEVICE_HIGH_AMOUNT";

    private final RuleProperties properties;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Optional<RuleHit> evaluate(TransactionEvent.Payload transaction, VelocityFeatures features) {
        RuleProperties.NewDeviceHighAmount config = properties.getNewDeviceHighAmount();
        if (!config.isEnabled() || !features.newDevice()) {
            return Optional.empty();
        }
        // Without enough history EVERY device looks new and "high" has no meaning
        if (features.historyCount() < config.getMinHistory()) {
            return Optional.empty();
        }
        BigDecimal threshold = features.averageAmount()
                .multiply(BigDecimal.valueOf(config.getAmountMultiplier()));
        // "At least": an amount exactly at the threshold fires the rule
        if (transaction.amount().compareTo(threshold) >= 0) {
            return Optional.of(new RuleHit(CODE, config.getScore(), String.format(Locale.ROOT,
                    "New device with amount %s, at least %.1fx this account's average of %s",
                    transaction.amount().toPlainString(), config.getAmountMultiplier(),
                    features.averageAmount().toPlainString())));
        }
        return Optional.empty();
    }
}