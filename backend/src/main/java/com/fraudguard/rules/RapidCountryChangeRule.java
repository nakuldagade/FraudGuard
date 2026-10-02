package com.fraudguard.rules;

import com.fraudguard.dto.RuleHit;
import com.fraudguard.dto.TransactionEvent;
import com.fraudguard.dto.VelocityFeatures;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// The account appears in a different country very soon after its previous transaction.
// NOTE: device and location values in this demo are synthetic. A real "impossible travel" rule
// would use real coordinates and distances, not just a country code.
@Component
@RequiredArgsConstructor
public class RapidCountryChangeRule implements FraudRule {

    public static final String CODE = "RAPID_COUNTRY_CHANGE";

    private final RuleProperties properties;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Optional<RuleHit> evaluate(TransactionEvent.Payload transaction, VelocityFeatures features) {
        RuleProperties.RapidCountryChange config = properties.getRapidCountryChange();
        if (!config.isEnabled() || !features.countryChanged()) {
            return Optional.empty();
        }
        Long seconds = features.secondsSincePreviousTransaction();
        if (seconds == null || seconds > config.getWithinSeconds()) {
            return Optional.empty();
        }
        return Optional.of(new RuleHit(CODE, config.getScore(),
                "Country changed to " + transaction.country() + " only " + seconds
                        + " seconds after the previous transaction"));
    }
}