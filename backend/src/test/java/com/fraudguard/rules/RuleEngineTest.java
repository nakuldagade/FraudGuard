package com.fraudguard.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.fraudguard.dto.RuleHit;
import com.fraudguard.dto.TransactionEvent;
import com.fraudguard.dto.VelocityFeatures;
import com.fraudguard.entity.TransactionStatus;
import com.fraudguard.entity.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// Plain unit tests: no Spring, no Docker. "new RuleProperties()" gives the default thresholds
// (the same as application.yml): 5 per 5 min, 20 per hour, z-score 3, min amount 1000, 3x average...
class RuleEngineTest {

    private final RuleProperties props = new RuleProperties();
    private final RuleEngine engine = new RuleEngine(List.of(
            new HighVelocityRule(props),
            new UnusualAmountRule(props),
            new NewDeviceHighAmountRule(props),
            new RapidCountryChangeRule(props)), props);

    // ---------- rule boundaries ----------

    @Test
    void velocityRuleFiresOnlyAboveTheLimit() {
        HighVelocityRule rule = new HighVelocityRule(props);

        assertThat(rule.evaluate(tx("100"), features().count5m(5).build())).isEmpty();   // exactly at the limit
        assertThat(rule.evaluate(tx("100"), features().count5m(6).build())).isPresent(); // one above
        assertThat(rule.evaluate(tx("100"), features().count1h(20).build())).isEmpty();
        assertThat(rule.evaluate(tx("100"), features().count1h(21).build())).isPresent();
    }

    @Test
    void unusualAmountNeedsHistoryHighZScoreAndMinimumAmount() {
        UnusualAmountRule rule = new UnusualAmountRule(props);

        assertThat(rule.evaluate(tx("5000"), features().z(null).build())).isEmpty();   // no history: never guess
        assertThat(rule.evaluate(tx("5000"), features().z(2.99).build())).isEmpty();   // just below 3.0
        assertThat(rule.evaluate(tx("5000"), features().z(3.0).build())).isPresent();  // exactly 3.0 fires
        assertThat(rule.evaluate(tx("999"), features().z(10.0).build())).isEmpty();    // below minimum amount
        assertThat(rule.evaluate(tx("1000"), features().z(10.0).build())).isPresent(); // exactly the minimum
    }

    @Test
    void newDeviceRuleNeedsNewDeviceHistoryAndAmountAtLeastThreeTimesAverage() {
        NewDeviceHighAmountRule rule = new NewDeviceHighAmountRule(props);   // average 100 -> threshold 300

        assertThat(rule.evaluate(tx("5000"), features().newDevice(false).history(10).build())).isEmpty();
        assertThat(rule.evaluate(tx("5000"), features().newDevice(true).history(4).build())).isEmpty();      // too little history
        assertThat(rule.evaluate(tx("299.99"), features().newDevice(true).history(5).build())).isEmpty();    // just below 3x
        assertThat(rule.evaluate(tx("300"), features().newDevice(true).history(5).build())).isPresent();     // exactly 3x fires
    }

    @Test
    void countryChangeRuleOnlyFiresWithinTheTimeWindow() {
        RapidCountryChangeRule rule = new RapidCountryChangeRule(props);

        assertThat(rule.evaluate(tx("100"), features().countryChanged(true).secs(3600L).build())).isPresent();
        assertThat(rule.evaluate(tx("100"), features().countryChanged(true).secs(3601L).build())).isEmpty();
        assertThat(rule.evaluate(tx("100"), features().countryChanged(true).secs(null).build())).isEmpty();
        assertThat(rule.evaluate(tx("100"), features().countryChanged(false).secs(10L).build())).isEmpty();
    }

    // ---------- combinations and decisions ----------

    @Test
    void quietTransactionIsApproved() {
        RiskAssessment result = engine.assess(tx("100"), features().build());

        assertThat(result.score()).isZero();
        assertThat(result.status()).isEqualTo(TransactionStatus.APPROVED);
        assertThat(result.hits()).isEmpty();
    }

    @Test
    void oneStrongSignalMeansReview() {
        RiskAssessment result = engine.assess(tx("100"), features().count5m(6).build());

        assertThat(result.score()).isEqualTo(40);
        assertThat(result.status()).isEqualTo(TransactionStatus.REVIEW);
        assertThat(result.hits()).extracting(RuleHit::code).containsExactly("HIGH_VELOCITY");
    }

    @Test
    void weakSignalAloneStaysApprovedButIsRecorded() {
        // new device with 4x the average: 30 points, below the review threshold of 40
        RiskAssessment result = engine.assess(tx("400"), features().newDevice(true).history(5).build());

        assertThat(result.score()).isEqualTo(30);
        assertThat(result.status()).isEqualTo(TransactionStatus.APPROVED);
        assertThat(result.hits()).extracting(RuleHit::code).containsExactly("NEW_DEVICE_HIGH_AMOUNT");
    }

    @Test
    void combinedSignalsAreBlocked() {
        // unusual amount (50) + new device (30) = 80, exactly the block threshold
        RiskAssessment result = engine.assess(tx("5000"),
                features().z(5.0).newDevice(true).history(6).build());

        assertThat(result.score()).isEqualTo(80);
        assertThat(result.status()).isEqualTo(TransactionStatus.BLOCKED);
    }

    @Test
    void scoreIsCappedAtOneHundredAndHitsAreSortedByScore() {
        RiskAssessment result = engine.assess(tx("5000"), features()
                .count5m(7).z(50.0).newDevice(true).history(6).countryChanged(true).secs(1L).build());

        assertThat(result.score()).isEqualTo(100);   // 50 + 40 + 35 + 30 = 155, capped
        assertThat(result.status()).isEqualTo(TransactionStatus.BLOCKED);
        assertThat(result.hits()).extracting(RuleHit::code).containsExactly(
                "UNUSUAL_AMOUNT", "HIGH_VELOCITY", "RAPID_COUNTRY_CHANGE", "NEW_DEVICE_HIGH_AMOUNT");
    }

    @Test
    void disabledRuleNeverFires() {
        props.getHighVelocity().setEnabled(false);

        RiskAssessment result = engine.assess(tx("100"), features().count5m(99).build());

        assertThat(result.hits()).isEmpty();
        assertThat(result.status()).isEqualTo(TransactionStatus.APPROVED);
    }

    // ---------- helpers ----------

    private static TransactionEvent.Payload tx(String amount) {
        return new TransactionEvent.Payload(UUID.randomUUID(), "acc-1", "m-1", TransactionType.PAYMENT,
                new BigDecimal(amount), "INR", "dev-1", "IN", Instant.now());
    }

    // Starts as a "quiet" account and lets each test change only what it cares about
    private static Fx features() {
        return new Fx();
    }

    private static class Fx {
        int count5m = 1;
        int count1h = 1;
        int history = 0;
        Double z = null;
        boolean newDevice = false;
        boolean countryChanged = false;
        Long secs = null;
        String average = "100";

        Fx count5m(int v) { count5m = v; return this; }
        Fx count1h(int v) { count1h = v; return this; }
        Fx history(int v) { history = v; return this; }
        Fx z(Double v) { z = v; return this; }
        Fx newDevice(boolean v) { newDevice = v; return this; }
        Fx countryChanged(boolean v) { countryChanged = v; return this; }
        Fx secs(Long v) { secs = v; return this; }

        VelocityFeatures build() {
            return new VelocityFeatures(count5m, count1h, BigDecimal.ZERO, BigDecimal.ZERO, history,
                    new BigDecimal(average), BigDecimal.ONE, z, newDevice, false, secs, countryChanged);
        }
    }
}