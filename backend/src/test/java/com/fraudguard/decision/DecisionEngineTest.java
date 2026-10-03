package com.fraudguard.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.fraudguard.dto.MlScoreResponse;
import com.fraudguard.dto.RuleHit;
import com.fraudguard.dto.VelocityFeatures;
import com.fraudguard.entity.TransactionStatus;
import com.fraudguard.rules.RiskAssessment;
import com.fraudguard.rules.RuleProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

// Plain unit tests: no Spring, no Docker. "new ...Properties()" gives the default thresholds.
class DecisionEngineTest {

    private final RuleProperties ruleProps = new RuleProperties();       // rule bands: review 40, block 80
    private final DecisionProperties props = new DecisionProperties();   // ML: min history 5, elevated 0.66, very high 0.70
    private final DecisionEngine engine = new DecisionEngine(props, ruleProps);

    // ---------- the policy table: every combination of "what the rules said" x "what the ML score said" ----------

    @ParameterizedTest(name = "rules {0} + ML {1} -> {2}")
    @CsvSource({
            "APPROVED, UNAVAILABLE,         APPROVED",
            "APPROVED, IGNORED_NEW_ACCOUNT, APPROVED",
            "APPROVED, NORMAL,              APPROVED",
            "APPROVED, ELEVATED,            REVIEW",     // ML alone can send a clean transaction to review
            "APPROVED, VERY_HIGH,           REVIEW",     // ...but ML alone never blocks
            "REVIEW,   UNAVAILABLE,         REVIEW",
            "REVIEW,   IGNORED_NEW_ACCOUNT, REVIEW",
            "REVIEW,   NORMAL,              REVIEW",
            "REVIEW,   ELEVATED,            REVIEW",
            "REVIEW,   VERY_HIGH,           BLOCKED",    // rules and ML agree strongly
            "BLOCKED,  UNAVAILABLE,         BLOCKED",
            "BLOCKED,  IGNORED_NEW_ACCOUNT, BLOCKED",
            "BLOCKED,  NORMAL,              BLOCKED",
            "BLOCKED,  ELEVATED,            BLOCKED",
            "BLOCKED,  VERY_HIGH,           BLOCKED"
    })
    void policyTable(TransactionStatus ruleStatus, MlSignal signal, TransactionStatus expected) {
        Decision decision = engine.decide(rules(ruleStatus), mlFor(signal), features(historyFor(signal)));

        assertThat(decision.status()).isEqualTo(expected);
        assertThat(decision.inputs().mlSignal()).isEqualTo(signal);
    }

    // ---------- boundaries ----------

    @ParameterizedTest(name = "ML score {0} -> {1}")
    @CsvSource({"0.659, NORMAL", "0.66, ELEVATED", "0.699, ELEVATED", "0.70, VERY_HIGH"})
    void mlThresholdsAreInclusive(double score, MlSignal expected) {
        Decision decision = engine.decide(rules(TransactionStatus.APPROVED), Optional.of(ml(score)), features(20));

        assertThat(decision.inputs().mlSignal()).isEqualTo(expected);
    }

    @Test
    void accountWithTooLittleHistoryIsNotJudgedByTheMlScore() {
        // 4 earlier transactions: not enough, even for a very high score
        Decision fourEarlier = engine.decide(rules(TransactionStatus.APPROVED), Optional.of(ml(0.90)), features(4));
        assertThat(fourEarlier.inputs().mlSignal()).isEqualTo(MlSignal.IGNORED_NEW_ACCOUNT);
        assertThat(fourEarlier.status()).isEqualTo(TransactionStatus.APPROVED);
        assertThat(fourEarlier.reasonCodes()).containsExactly("ML_IGNORED_NEW_ACCOUNT");
        assertThat(fourEarlier.explanation()).contains("only 4 earlier transactions");

        // 5 earlier transactions: the score counts
        Decision fiveEarlier = engine.decide(rules(TransactionStatus.APPROVED), Optional.of(ml(0.90)), features(5));
        assertThat(fiveEarlier.inputs().mlSignal()).isEqualTo(MlSignal.VERY_HIGH);
        assertThat(fiveEarlier.status()).isEqualTo(TransactionStatus.REVIEW);
    }

    // ---------- reasons and explanations ----------

    @Test
    void reasonCodesListRulesFirstThenMlThenAgreement() {
        Decision decision = engine.decide(rules(TransactionStatus.REVIEW), Optional.of(ml(0.75)), features(20));

        assertThat(decision.reasonCodes())
                .containsExactly("HIGH_VELOCITY", "ML_ANOMALY_VERY_HIGH", "RULES_AND_ML_AGREE");
        assertThat(decision.explanation())
                .startsWith("BLOCKED.")
                .contains("Rules scored 40/100 (HIGH_VELOCITY)")
                .contains("very high")
                .contains("so the transaction is blocked");
    }

    @Test
    void quietTransactionSaysThereWereNoRiskSignals() {
        Decision decision = engine.decide(rules(TransactionStatus.APPROVED), Optional.of(ml(0.40)), features(20));

        assertThat(decision.status()).isEqualTo(TransactionStatus.APPROVED);
        assertThat(decision.reasonCodes()).containsExactly("NO_RISK_SIGNALS");
        assertThat(decision.explanation()).contains("No fraud rule fired").contains("No signal reached a review level");
    }

    @Test
    void unavailableMlIsStatedInTheDecision() {
        Decision decision = engine.decide(rules(TransactionStatus.BLOCKED), Optional.empty(), features(20));

        assertThat(decision.status()).isEqualTo(TransactionStatus.BLOCKED);
        assertThat(decision.reasonCodes()).contains("ML_UNAVAILABLE");
        assertThat(decision.explanation())
                .contains("85/100").contains("UNUSUAL_AMOUNT")
                .contains("rules only").contains("block level of 80");
    }

    // ---------- stored inputs and configurability ----------

    @Test
    void decisionKeepsASnapshotOfEverythingItWasBasedOn() {
        VelocityFeatures features = features(20);
        Decision decision = engine.decide(rules(TransactionStatus.REVIEW), Optional.of(ml(0.75)), features);

        DecisionInputs inputs = decision.inputs();
        assertThat(decision.policyVersion()).isEqualTo("policy-v1");
        assertThat(inputs.policyVersion()).isEqualTo("policy-v1");
        assertThat(inputs.velocityFeatures()).isEqualTo(features);
        assertThat(inputs.ruleScore()).isEqualTo(40);
        assertThat(inputs.ruleStatus()).isEqualTo(TransactionStatus.REVIEW);
        assertThat(inputs.ruleHits()).hasSize(1);
        assertThat(inputs.anomalyScore()).isEqualTo(0.75);
        assertThat(inputs.modelVersion()).isEqualTo("isoforest-v1");
        assertThat(inputs.thresholds()).isEqualTo(new DecisionInputs.Thresholds(40, 80, 5, 0.66, 0.70));
    }

    @Test
    void changingThePolicyNumbersChangesTheDecision() {
        props.setMlReviewAt(0.90);       // be much less sensitive to the ML score
        props.setMlVeryHighAt(0.95);
        props.setPolicyVersion("policy-v2");

        Decision decision = engine.decide(rules(TransactionStatus.APPROVED), Optional.of(ml(0.68)), features(20));

        assertThat(decision.status()).isEqualTo(TransactionStatus.APPROVED);   // 0.68 is no longer "elevated"
        assertThat(decision.policyVersion()).isEqualTo("policy-v2");
    }

    // ---------- helpers ----------

    private static MlScoreResponse ml(double score) {
        return new MlScoreResponse("t", score, "isoforest-v1", "1");
    }

    // The ML answer that produces each kind of signal
    private static Optional<MlScoreResponse> mlFor(MlSignal signal) {
        return switch (signal) {
            case UNAVAILABLE -> Optional.empty();
            case IGNORED_NEW_ACCOUNT -> Optional.of(ml(0.90));
            case NORMAL -> Optional.of(ml(0.40));
            case ELEVATED -> Optional.of(ml(0.68));
            case VERY_HIGH -> Optional.of(ml(0.75));
        };
    }

    private static int historyFor(MlSignal signal) {
        return signal == MlSignal.IGNORED_NEW_ACCOUNT ? 2 : 20;
    }

    private static VelocityFeatures features(int history) {
        return new VelocityFeatures(1, 1, BigDecimal.TEN, BigDecimal.TEN, history,
                new BigDecimal("100.00"), new BigDecimal("5.00"), 0.5, false, false, 3600L, false);
    }

    private static RiskAssessment rules(TransactionStatus status) {
        return switch (status) {
            case REVIEW -> new RiskAssessment(40, status,
                    List.of(new RuleHit("HIGH_VELOCITY", 40, "7 transactions in the last 5 minutes")));
            case BLOCKED -> new RiskAssessment(85, status, List.of(
                    new RuleHit("UNUSUAL_AMOUNT", 50, "amount far above average"),
                    new RuleHit("HIGH_VELOCITY", 35, "7 transactions in the last 5 minutes")));
            default -> new RiskAssessment(0, TransactionStatus.APPROVED, List.of());
        };
    }
}