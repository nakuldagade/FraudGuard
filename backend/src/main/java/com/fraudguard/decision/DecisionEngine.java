package com.fraudguard.decision;

import com.fraudguard.dto.MlScoreResponse;
import com.fraudguard.dto.RuleHit;
import com.fraudguard.dto.VelocityFeatures;
import com.fraudguard.entity.TransactionStatus;
import com.fraudguard.rules.RiskAssessment;
import com.fraudguard.rules.RuleProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Combines the rule verdict, the ML score and the velocity features into the final decision.
 *
 * THE POLICY (policy-v1):
 *   1. Start from the rule engine's verdict (APPROVED / REVIEW / BLOCKED).
 *   2. Classify the ML score: UNAVAILABLE, IGNORED_NEW_ACCOUNT (too little history),
 *      NORMAL, ELEVATED (>= mlReviewAt) or VERY_HIGH (>= mlVeryHighAt).
 *   3. ELEVATED or VERY_HIGH on a transaction the rules approved -> REVIEW.
 *      (ML alone can send something to review, but never blocks.)
 *   4. VERY_HIGH on a transaction the rules sent to REVIEW -> BLOCKED (rules and ML agree).
 *   5. Anything else keeps the rule verdict.
 *
 * Note: the ML anomaly score is a ranking score. It is NOT a calibrated probability of fraud,
 * and nothing here treats it as one.
 */
@Service
@RequiredArgsConstructor
public class DecisionEngine {

    static final int MAX_EXPLANATION_LENGTH = 1000;   // matches the database column

    private final DecisionProperties properties;
    private final RuleProperties ruleProperties;      // for the rule score bands (review / block)

    public Decision decide(RiskAssessment rules, Optional<MlScoreResponse> ml, VelocityFeatures features) {
        MlSignal signal = classify(ml, features);
        TransactionStatus ruleStatus = rules.status();

        // ---- the policy ----
        TransactionStatus status = ruleStatus;
        boolean sentToReviewByMl = false;
        boolean upgradedToBlock = false;
        boolean mlConcerned = signal == MlSignal.ELEVATED || signal == MlSignal.VERY_HIGH;
        if (mlConcerned && ruleStatus == TransactionStatus.APPROVED) {
            status = TransactionStatus.REVIEW;
            sentToReviewByMl = true;
        }
        if (signal == MlSignal.VERY_HIGH && ruleStatus == TransactionStatus.REVIEW) {
            status = TransactionStatus.BLOCKED;
            upgradedToBlock = true;
        }

        List<String> reasonCodes = reasonCodes(rules, signal, upgradedToBlock);
        String explanation = explain(status, rules, ml, signal, features, sentToReviewByMl, upgradedToBlock);

        DecisionInputs inputs = new DecisionInputs(
                properties.getPolicyVersion(), Instant.now(), features,
                rules.score(), ruleStatus, rules.hits(),
                ml.map(MlScoreResponse::anomalyScore).orElse(null),
                ml.map(MlScoreResponse::modelVersion).orElse(null),
                signal,
                new DecisionInputs.Thresholds(
                        ruleProperties.getDecision().getReviewAt(), ruleProperties.getDecision().getBlockAt(),
                        properties.getMlMinHistory(), properties.getMlReviewAt(), properties.getMlVeryHighAt()));

        return new Decision(status, reasonCodes, explanation, properties.getPolicyVersion(), inputs);
    }

    // How should the ML score be treated for this transaction?
    private MlSignal classify(Optional<MlScoreResponse> ml, VelocityFeatures features) {
        if (ml.isEmpty()) {
            return MlSignal.UNAVAILABLE;
        }
        // A brand-new account has no baseline: every device and merchant looks "new", which inflates scores
        if (features.historyCount() < properties.getMlMinHistory()) {
            return MlSignal.IGNORED_NEW_ACCOUNT;
        }
        double score = ml.get().anomalyScore();
        if (score >= properties.getMlVeryHighAt()) {
            return MlSignal.VERY_HIGH;
        }
        if (score >= properties.getMlReviewAt()) {
            return MlSignal.ELEVATED;
        }
        return MlSignal.NORMAL;
    }

    // Rule codes first (biggest contributor first), then what the ML score contributed
    private List<String> reasonCodes(RiskAssessment rules, MlSignal signal, boolean upgradedToBlock) {
        List<String> codes = new ArrayList<>(rules.hits().stream().map(RuleHit::code).toList());
        switch (signal) {
            case ELEVATED -> codes.add("ML_ANOMALY_ELEVATED");
            case VERY_HIGH -> codes.add("ML_ANOMALY_VERY_HIGH");
            case UNAVAILABLE -> codes.add("ML_UNAVAILABLE");
            case IGNORED_NEW_ACCOUNT -> codes.add("ML_IGNORED_NEW_ACCOUNT");
            case NORMAL -> { }
        }
        if (upgradedToBlock) {
            codes.add("RULES_AND_ML_AGREE");
        }
        if (codes.isEmpty()) {
            codes.add("NO_RISK_SIGNALS");
        }
        return List.copyOf(codes);
    }

    // The same story in plain English, with the real numbers
    private String explain(TransactionStatus status, RiskAssessment rules, Optional<MlScoreResponse> ml,
                           MlSignal signal, VelocityFeatures features,
                           boolean sentToReviewByMl, boolean upgradedToBlock) {
        double score = ml.map(MlScoreResponse::anomalyScore).orElse(Double.NaN);
        String model = ml.map(MlScoreResponse::modelVersion).orElse("none");
        StringBuilder text = new StringBuilder(status.name()).append(". ");

        // 1) what the rules found
        if (rules.hits().isEmpty()) {
            text.append("No fraud rule fired. ");
        } else {
            text.append(String.format(Locale.ROOT, "Rules scored %d/100 (%s). ", rules.score(),
                    String.join(", ", rules.hits().stream().map(RuleHit::code).toList())));
        }

        // 2) what the ML score said, and how it was treated
        text.append(switch (signal) {
            case UNAVAILABLE -> "The ML score was unavailable, so the decision uses the rules only. ";
            case IGNORED_NEW_ACCOUNT -> String.format(Locale.ROOT,
                    "The ML score (%.2f) was ignored because the account has only %d earlier transactions (needs %d). ",
                    score, features.historyCount(), properties.getMlMinHistory());
            case NORMAL -> String.format(Locale.ROOT,
                    "ML anomaly score %.2f (%s) is in the normal range. ", score, model);
            case ELEVATED -> String.format(Locale.ROOT,
                    "ML anomaly score %.2f (%s) is elevated (at least %.2f). ", score, model, properties.getMlReviewAt());
            case VERY_HIGH -> String.format(Locale.ROOT,
                    "ML anomaly score %.2f (%s) is very high (at least %.2f). ", score, model, properties.getMlVeryHighAt());
        });

        // 3) why that adds up to this status
        if (upgradedToBlock) {
            text.append("Rules asked for a review and the ML score is very high, so the transaction is blocked.");
        } else if (sentToReviewByMl) {
            text.append("No rule asked for action, but the ML score is elevated, so the transaction is sent for "
                    + "review (the ML score alone never blocks).");
        } else if (rules.status() == TransactionStatus.BLOCKED) {
            text.append(String.format(Locale.ROOT, "The rule score reached the block level of %d.",
                    ruleProperties.getDecision().getBlockAt()));
        } else if (rules.status() == TransactionStatus.REVIEW) {
            text.append(String.format(Locale.ROOT, "The rule score reached the review level of %d.",
                    ruleProperties.getDecision().getReviewAt()));
        } else {
            text.append("No signal reached a review level.");
        }

        String result = text.toString();
        return result.length() <= MAX_EXPLANATION_LENGTH ? result : result.substring(0, MAX_EXPLANATION_LENGTH);
    }
}